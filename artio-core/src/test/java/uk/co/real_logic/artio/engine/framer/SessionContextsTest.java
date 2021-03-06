/*
 * Copyright 2015-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.framer;

import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;
import uk.co.real_logic.artio.FileSystemCorruptionException;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.LogonEncoder;
import uk.co.real_logic.artio.decoder.HeaderDecoder;
import uk.co.real_logic.artio.decoder.LogonDecoder;
import uk.co.real_logic.artio.engine.MappedFile;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionIdStrategy;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.engine.framer.SessionContexts.DUPLICATE_SESSION;
import static uk.co.real_logic.artio.engine.framer.SessionContexts.LOWEST_VALID_SESSION_ID;

public class SessionContextsTest
{
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final int SEQUENCE_INDEX = 1;
    private static final int FILE_POSITION = 0;

    private ErrorHandler errorHandler = mock(ErrorHandler.class);
    private AtomicBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(BUFFER_SIZE));
    private MappedFile mappedFile = mock(MappedFile.class);
    private SessionIdStrategy idStrategy = SessionIdStrategy.senderAndTarget();
    private SessionContexts sessionContexts = newSessionContexts(buffer);
    private MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer(ByteBuffer.allocate(BUFFER_SIZE));
    private LogonEncoder logonEncoder = new LogonEncoder();

    private CompositeKey aSession = idStrategy.onInitiateLogon("a", null, null, "b", null, null);
    private CompositeKey bSession = idStrategy.onInitiateLogon("b", null, null, "a", null, null);
    private CompositeKey cSession = idStrategy.onInitiateLogon("c", null, null, "c", null, null);

    @Test
    public void sessionContextsAreUnique()
    {
        assertNotEquals(sessionContexts.onLogon(aSession), sessionContexts.onLogon(bSession));
    }

    @Test
    public void findsDuplicateSessions()
    {
        sessionContexts.onLogon(aSession);

        assertEquals(SessionContexts.DUPLICATE_SESSION, sessionContexts.onLogon(aSession));
    }

    @Test
    public void handsOutSameSessionContextAfterDisconnect()
    {
        final SessionContext sessionContext = sessionContexts.onLogon(aSession);
        sessionContexts.onDisconnect(sessionContext.sessionId());

        assertValuesEqual(sessionContext, sessionContexts.onLogon(aSession));
    }

    @Test
    public void persistsSessionContextsOverARestart()
    {
        final SessionContext bContext = sessionContexts.onLogon(bSession);
        final SessionContext aContext = sessionContexts.onLogon(aSession);

        bContext.onSequenceReset();
        aContext.onSequenceReset();

        final SessionContexts sessionContextsAfterRestart = newSessionContexts(buffer);
        assertValuesEqual(aContext, sessionContextsAfterRestart.onLogon(aSession));
        assertValuesEqual(bContext, sessionContextsAfterRestart.onLogon(bSession));
    }

    @Test
    public void continuesIncrementingSessionContextsAfterRestart()
    {
        final SessionContext bContext = sessionContexts.onLogon(bSession);
        final SessionContext aContext = sessionContexts.onLogon(aSession);

        final SessionContexts sessionContextsAfterRestart = newSessionContexts(buffer);

        final SessionContext cContext = sessionContextsAfterRestart.onLogon(cSession);
        assertValidSessionId(cContext.sessionId());
        assertNotEquals("C is a duplicate of A", aContext, cContext);
        assertNotEquals("C is a duplicate of B", bContext, cContext);
    }

    @Test
    public void checksFileCorruption()
    {
        sessionContexts.onLogon(bSession);
        sessionContexts.onLogon(aSession);

        // corrupt buffer
        buffer.putBytes(8, new byte[1024]);

        newSessionContexts(buffer);

        verify(errorHandler).onError(any(FileSystemCorruptionException.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void validateSizeOfBuffer()
    {
        final AtomicBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(1024));
        newSessionContexts(buffer);
    }

    @Test
    public void wrapsOverSectorBoundaries()
    {
        final int requiredNumberOfWritesToSpanSector = 232;

        final List<CompositeKey> keys = IntStream
            .range(0, requiredNumberOfWritesToSpanSector)
            .mapToObj((i) -> idStrategy.onInitiateLogon("b" + i, null, null, "a" + i, null, null))
            .collect(toList());

        final List<SessionContext> contexts = keys
            .stream()
            .map(sessionContexts::onLogon)
            .peek(SessionContext::onSequenceReset)
            .collect(toList());

        // Test an update of something not at the tail of the buffer.
        final SessionContext firstContext = contexts.get(0);
        firstContext.onSequenceReset();

        final SessionContexts contextsAfterRestart = newSessionContexts(buffer);
        IntStream
            .range(0, requiredNumberOfWritesToSpanSector)
            .forEach((i) -> assertValuesEqual(contexts.get(i), contextsAfterRestart.onLogon(keys.get(i))));
    }

    @Test
    public void resetsSessionContexts()
    {
        final SessionContext aContext = sessionContexts.onLogon(aSession);
        sessionContexts.onDisconnect(aContext.sessionId());

        sessionContexts.reset(null);

        assertSessionContextsReset(aContext, sessionContexts);

        verifyNoBackUp();
    }

    @Test
    public void resetsSessionContextsFile()
    {
        final SessionContext aContext = sessionContexts.onLogon(aSession);
        sessionContexts.onDisconnect(aContext.sessionId());

        sessionContexts.reset(null);

        final SessionContexts sessionContextsAfterRestart = newSessionContexts(buffer);

        assertSessionContextsReset(aContext, sessionContextsAfterRestart);

        verifyNoBackUp();
    }

    @Test
    public void copiesOldSessionContextFile() throws IOException
    {
        final File backupLocation = File.createTempFile("sessionContexts", "tmp");
        try
        {
            final SessionContext aContext = sessionContexts.onLogon(aSession);
            sessionContexts.onDisconnect(aContext.sessionId());

            final byte[] oldData = new byte[BUFFER_SIZE];
            buffer.getBytes(0, oldData);

            sessionContexts.reset(backupLocation);

            verify(mappedFile).transferTo(backupLocation);
        }
        finally
        {
            IoUtil.deleteIfExists(backupLocation);
        }
    }

    @Test
    public void handsOutSameSessionContextAfterTakingOverAsLeader()
    {
        final long sessionId = 123;
        final HeaderDecoder header = mock(HeaderDecoder.class);
        when(header.senderCompIDAsString()).thenReturn(aSession.localCompId());
        when(header.targetCompIDAsString()).thenReturn(aSession.remoteCompId());

        sessionContexts.onSentFollowerLogon(header, sessionId, SEQUENCE_INDEX);

        final SessionContext sessionContext = sessionContexts.onLogon(aSession);
        assertValuesEqual(
            sessionContext,
            new SessionContext(sessionId, SEQUENCE_INDEX, Session.NO_LOGON_TIME, sessionContexts, FILE_POSITION));
    }

    @Test
    public void doesNotReuseExistingSessionIdsForDistinctCompositeKeys()
    {
        final SessionContext aContext = sessionContexts.onLogon(aSession);
        final SessionContext bContext = sessionContexts.onLogon(bSession); // bump counter

        final long result = logonWithSenderAndTarget(aSession.localCompId(), aSession.remoteCompId());

        sessionContexts.onSentFollowerMessage(
            aContext.sessionId(), aContext.sequenceIndex(), LogonDecoder.MESSAGE_TYPE, asciiBuffer,
            Encoder.offset(result), Encoder.length(result));

        final SessionContext cContext = sessionContexts.onLogon(cSession);

        assertNotEquals(DUPLICATE_SESSION, cContext);
        assertEquals(3, cContext.sessionId());
    }

    private void verifyNoBackUp()
    {
        verify(mappedFile, never()).transferTo(any());
    }

    private void assertSessionContextsReset(final SessionContext aContext, final SessionContexts sessionContexts)
    {
        final SessionContext bContext = sessionContexts.onLogon(bSession);
        final SessionContext newAContext = sessionContexts.onLogon(aSession);
        assertValidSessionId(bContext.sessionId());
        assertValidSessionId(newAContext.sessionId());
        assertEquals("Session Contexts haven't been reset", aContext, bContext);
        assertNotEquals("Session Contexts haven't been reset", aContext, newAContext);
    }

    private void assertValidSessionId(final long cId)
    {
        assertThat(cId, greaterThanOrEqualTo(LOWEST_VALID_SESSION_ID));
    }

    private SessionContexts newSessionContexts(final AtomicBuffer buffer)
    {
        when(mappedFile.buffer()).thenReturn(buffer);
        return new SessionContexts(mappedFile, idStrategy, errorHandler);
    }

    private void assertValuesEqual(
        final SessionContext sessionContext,
        final SessionContext secondSessionContext)
    {
        assertEquals(sessionContext, secondSessionContext);
        assertEquals(sessionContext.sequenceIndex(), secondSessionContext.sequenceIndex());
    }

    private long logonWithSenderAndTarget(final String senderCompID, final String targetCompID)
    {
        logonEncoder.header()
            .sendingTime(new byte[] {0})
            .senderCompID(senderCompID)
            .targetCompID(targetCompID);
        return logonEncoder.encryptMethod(0).heartBtInt(0).encode(asciiBuffer, 0);
    }
}
