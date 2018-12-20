package org.openbase.bco.authentication.lib;

/*-
 * #%L
 * BCO Authentication Library
 * %%
 * Copyright (C) 2017 - 2018 openbase.org
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import com.google.protobuf.ByteString;
import org.openbase.bco.authentication.lib.exception.SessionExpiredException;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.jul.exception.RejectedException;
import org.openbase.jul.extension.type.processing.TimestampJavaTimeTransform;
import org.openbase.jul.extension.type.processing.TimestampProcessor;
import org.openbase.type.domotic.authentication.AuthenticatorType.Authenticator;
import org.openbase.type.domotic.authentication.TicketAuthenticatorWrapperType.TicketAuthenticatorWrapper;
import org.openbase.type.domotic.authentication.TicketSessionKeyWrapperType.TicketSessionKeyWrapper;
import org.openbase.type.domotic.authentication.TicketType.Ticket;
import org.openbase.type.timing.IntervalType.Interval;
import org.openbase.type.timing.TimestampType.Timestamp;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:sfast@techfak.uni-bielefeld.de">Sebastian Fast</a>
 */
public class AuthenticationServerHandler {

    public static final long MAX_TIME_DIFF_SERVER_CLIENT = TimeUnit.MINUTES.toNanos(2);

    /**
     * Handles a Key Distribution Center (KDC) login request
     * Creates a Ticket Granting Server (TGS) session key that is encrypted by the client's password
     * Creates a Ticket Granting Ticket (TGT) that is encrypted by TGS private key
     *
     * @param id                             identifier of the client or user
     * @param userKey                        hashed password or public key of the user
     * @param clientKey                      public key of the client respectively
     * @param clientNetworkAddress           Network address of client
     * @param ticketGrantingServiceSecretKey TGS secret key generated by controller or saved somewhere in the system
     * @param validityTime                   the time in milliseconds from now how long the TGT is valid
     * @return Returns wrapper class containing both the TGT and TGS session key
     * @throws NotAvailableException    Throws, if clientID was not found in database
     * @throws CouldNotPerformException If the data for the remotes has not been synchronized yet.
     * @throws InterruptedException     If the Registry thread is interrupted externally.
     * @throws IOException              If an encryption operation fails because of a general I/O error.
     */
    public static TicketSessionKeyWrapper handleKDCRequest(final String id, final byte[] userKey, final byte[] clientKey, final String clientNetworkAddress, final byte[] ticketGrantingServiceSecretKey, final long validityTime) throws NotAvailableException, InterruptedException, CouldNotPerformException, IOException {
        byte[] ticketGrantingServiceSessionKey = EncryptionHelper.generateKey();

        // create ticket granting ticket
        Ticket.Builder ticketGrantingTicket = Ticket.newBuilder();
        ticketGrantingTicket.setClientId(id);
        ticketGrantingTicket.setClientIp(clientNetworkAddress);
        ticketGrantingTicket.setValidityPeriod(getValidityInterval(validityTime));
        ticketGrantingTicket.setSessionKeyBytes(ByteString.copyFrom(ticketGrantingServiceSessionKey));

        // create TicketSessionKeyWrapper
        TicketSessionKeyWrapper.Builder ticketSessionKeyWrapper = TicketSessionKeyWrapper.newBuilder();
        ticketSessionKeyWrapper.setTicket(EncryptionHelper.encryptSymmetric(ticketGrantingTicket.build(), ticketGrantingServiceSecretKey));

        if (userKey != null) {
            ticketGrantingServiceSessionKey = EncryptionHelper.encrypt(ticketGrantingServiceSessionKey, userKey, true);
        }
        if (clientKey != null) {
            ticketGrantingServiceSessionKey = EncryptionHelper.encrypt(ticketGrantingServiceSessionKey, clientKey, false);
        }

        ticketSessionKeyWrapper.setSessionKey(ByteString.copyFrom(ticketGrantingServiceSessionKey));

        return ticketSessionKeyWrapper.build();
    }

    /**
     * Handles a Ticket Granting Service (TGS) request
     * Creates a Service Server (SS) session key that is encrypted with the TGS session key
     * Creates a Client Server Ticket (CST) that is encrypted by SS private key
     *
     * @param ticketGrantingServiceSecretKey TGS secret key generated by controller or saved somewhere in the system
     * @param serviceServerSecretKey         TGS secret key generated by controller or saved somewhere in the system
     * @param wrapper                        TicketAuthenticatorWrapperWrapper that contains both encrypted Authenticator and TGT
     * @param validityTime                   time in milli seconds how long the new ticket is valid from now on
     * @return Returns a wrapper class containing both the CST and SS session key
     * @throws RejectedException        If timestamp in Authenticator does not fit to time period in TGT
     *                                  or, if clientID in Authenticator does not match clientID in TGT
     * @throws CouldNotPerformException If de- or encryption fail.
     */
    public static TicketSessionKeyWrapper handleTGSRequest(final byte[] ticketGrantingServiceSecretKey, final byte[] serviceServerSecretKey, final TicketAuthenticatorWrapper wrapper, final long validityTime) throws RejectedException, CouldNotPerformException {
        // decrypt ticket and authenticator
        Ticket ticketGrantingTicket = EncryptionHelper.decryptSymmetric(wrapper.getTicket(), ticketGrantingServiceSecretKey, Ticket.class);
        byte[] ticketGrantingServiceSessionKey = ticketGrantingTicket.getSessionKeyBytes().toByteArray();
        Authenticator authenticator = EncryptionHelper.decryptSymmetric(wrapper.getAuthenticator(), ticketGrantingServiceSessionKey, Authenticator.class);

        // compare clientIDs and timestamp to period
        AuthenticationServerHandler.validateTicket(ticketGrantingTicket, authenticator);

        // generate new session key
        byte[] serviceServerSessionKey = EncryptionHelper.generateKey();

        // update period and session key
        Ticket.Builder clientServerTicket = ticketGrantingTicket.toBuilder();
        clientServerTicket.setValidityPeriod(getValidityInterval(validityTime));
        clientServerTicket.setSessionKeyBytes(ByteString.copyFrom(serviceServerSessionKey));

        // create TicketSessionKeyWrapper
        TicketSessionKeyWrapper.Builder ticketSessionKeyWrapper = TicketSessionKeyWrapper.newBuilder();
        ticketSessionKeyWrapper.setTicket(EncryptionHelper.encryptSymmetric(clientServerTicket.build(), serviceServerSecretKey));
        ticketSessionKeyWrapper.setSessionKey(EncryptionHelper.encryptSymmetric(serviceServerSessionKey, ticketGrantingServiceSessionKey));

        return ticketSessionKeyWrapper.build();
    }

    /**
     * Handles a service method (Remote) request to Service Server (SS) (Manager).
     * Updates given CST's validity period and encrypt again by SS private key.
     * Adds 1 to the authenticator's timestamp to ensure the client that this server responded.
     *
     * @param serviceServerSecretKey SS secret key only known to SS
     * @param wrapper                TicketAuthenticatorWrapper wrapper that contains both encrypted Authenticator and TGT
     * @param validityTime           time in milli seconds how long the new ticket is valid from now on
     * @return Returns a wrapper class containing both the modified CST and unchanged Authenticator
     * @throws RejectedException        If timestamp in Authenticator does not fit to time period in TGT
     *                                  or, if clientID in Authenticator does not match clientID in TGT
     * @throws CouldNotPerformException If de- or encryption fail.
     */
    public static TicketAuthenticatorWrapper handleSSRequest(final byte[] serviceServerSecretKey, final TicketAuthenticatorWrapper wrapper, final long validityTime) throws CouldNotPerformException {
        // decrypt ticket and authenticator
        Ticket clientServerTicket = EncryptionHelper.decryptSymmetric(wrapper.getTicket(), serviceServerSecretKey, Ticket.class);
        Authenticator authenticator = EncryptionHelper.decryptSymmetric(wrapper.getAuthenticator(), clientServerTicket.getSessionKeyBytes().toByteArray(), Authenticator.class);

        // compare clientIDs and timestamp to period
        AuthenticationServerHandler.validateTicket(clientServerTicket, authenticator);

        // update period and session key
        Ticket.Builder cstb = clientServerTicket.toBuilder();
        cstb.setValidityPeriod(getValidityInterval(validityTime));

        // add 1 to authenticator's timestamp
        Authenticator.Builder ab = authenticator.toBuilder();
        ab.setTimestamp(ab.getTimestamp().toBuilder().setTime(ab.getTimestamp().getTime() + 1));

        // update TicketAuthenticatorWrapper
        TicketAuthenticatorWrapper.Builder ticketAuthenticatorWrapper = wrapper.toBuilder();
        ticketAuthenticatorWrapper.setTicket(EncryptionHelper.encryptSymmetric(cstb.build(), serviceServerSecretKey));
        ticketAuthenticatorWrapper.setAuthenticator(EncryptionHelper.encryptSymmetric(ab.build(), clientServerTicket.getSessionKeyBytes().toByteArray()));

        return ticketAuthenticatorWrapper.build();
    }

    public static void validateTicket(Ticket ticket, Authenticator authenticator) throws RejectedException {
        // validate that client and ids in authenticator and ticket match
        if (!ticket.hasClientId() || ticket.getClientId().isEmpty()) {
            throw new RejectedException("Ticket does not contain a client id");
        }
        if (!authenticator.hasClientId() || authenticator.getClientId().isEmpty()) {
            throw new RejectedException("Authenticator does not contain a client id");
        }
        if (!authenticator.getClientId().equals(ticket.getClientId())) {
            System.err.println("Received an erroneous request regarding the client id. Expected[" + ticket.getClientId() + "] but was[" + authenticator.getClientId() + "]");
            throw new RejectedException("ClientIds do not match");
        }

        // validate that the timestamp from the client request is inside the validation interval of the ticket
        if (!AuthenticationServerHandler.isTimestampInInterval(authenticator.getTimestamp(), ticket.getValidityPeriod())) {
            throw new SessionExpiredException();
        }

        // validate that the timestamp does not differ to much from the time of the server
        Timestamp currentTime = TimestampProcessor.getCurrentTimestamp();
        if (authenticator.getTimestamp().getTime() < (currentTime.getTime() - MAX_TIME_DIFF_SERVER_CLIENT) ||
                authenticator.getTimestamp().getTime() > (currentTime.getTime() + MAX_TIME_DIFF_SERVER_CLIENT)) {
            throw new SessionExpiredException();
        }
    }

    /**
     * Test if the timestamp lies in the interval
     *
     * @param timestamp the timestamp checked
     * @param interval  the interval checked
     * @return true if the timestamp is greater equals the start and lower equals the end of the interval
     */
    public static boolean isTimestampInInterval(final Timestamp timestamp, final Interval interval) {
        return timestamp.getTime() >= interval.getBegin().getTime() && timestamp.getTime() <= interval.getEnd().getTime();
    }

    /**
     * Generate an interval which begins now and has an end times 15 minutes from now.
     *
     * @param validityTime the time in milli seconds how long  the interval should go from now
     * @return the above described interval
     */
    public static Interval getValidityInterval(final long validityTime) {
        long currentTime = System.currentTimeMillis();
        Interval.Builder validityInterval = Interval.newBuilder();
        validityInterval.setBegin(TimestampJavaTimeTransform.transform(currentTime));
        validityInterval.setEnd(TimestampJavaTimeTransform.transform(currentTime + validityTime));
        return validityInterval.build();
    }
}
