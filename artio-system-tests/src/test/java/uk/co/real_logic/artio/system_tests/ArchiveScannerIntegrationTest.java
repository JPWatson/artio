/*
 * Copyright 2015-2018 Real Logic Ltd, Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.system_tests;

import org.agrona.CloseHelper;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.logger.FixArchiveScanner;
import uk.co.real_logic.artio.engine.logger.FixMessageConsumer;
import uk.co.real_logic.artio.library.LibraryConfiguration;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static uk.co.real_logic.artio.TestFixtures.largeTestReqId;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_ARCHIVE_SCANNER_STREAM;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class ArchiveScannerIntegrationTest extends AbstractGatewayToGatewaySystemTest
{
    private final FakeConnectHandler fakeConnectHandler = new FakeConnectHandler();

    @Before
    public void launch()
    {
        delete(ACCEPTOR_LOGS);

        mediaDriver = launchMediaDriver();

        launchAcceptingEngine();
        initiatingEngine = launchInitiatingEngine(libraryAeronPort);

        final LibraryConfiguration acceptingLibraryConfig = acceptingLibraryConfig(acceptingHandler);
        acceptingLibraryConfig.libraryConnectHandler(fakeConnectHandler);
        acceptingLibrary = connect(acceptingLibraryConfig);
        initiatingLibrary = newInitiatingLibrary(libraryAeronPort, initiatingHandler);
        testSystem = new TestSystem(acceptingLibrary, initiatingLibrary);

        connectSessions();
    }

    @Test
    public void canScanArchiveWhilstGatewayRunning()
    {
        setupAndExchangeMessages();

        assertArchiveContainsMessages("hi");
    }

    @Test
    public void canScanArchiveForLargeMessages()
    {
        acquireAcceptingSession();

        final String testReqID = largeTestReqId();

        sendTestRequest(acceptingSession, testReqID);

        assertReceivedSingleHeartbeat(testSystem, acceptingOtfAcceptor, testReqID);

        assertInitiatingSequenceIndexIs(0);

        assertArchiveContainsMessages(largeTestReqId());
    }

    @Test
    public void canScanArchiveWhenGatewayStopped()
    {
        setupAndExchangeMessages();

        CloseHelper.close(initiatingLibrary);
        CloseHelper.close(acceptingLibrary);

        CloseHelper.close(initiatingEngine);
        CloseHelper.close(acceptingEngine);

        assertArchiveContainsMessages("hi");
    }

    private void setupAndExchangeMessages()
    {
        messagesCanBeExchanged();

        assertInitiatingSequenceIndexIs(0);
    }

    @SuppressWarnings("unchecked")
    private void assertArchiveContainsMessages(final String testReqIdPrefix)
    {
        final List<String> messages = new ArrayList<>();
        final EngineConfiguration configuration = acceptingEngine.configuration();
        final FixMessageConsumer fixMessageConsumer =
            (message, buffer, offset, length, header) -> messages.add(message.body());

        final FixArchiveScanner.Context context = new FixArchiveScanner.Context()
            .aeronDirectoryName(configuration.aeronContext().aeronDirectoryName())
            .idleStrategy(CommonConfiguration.backoffIdleStrategy());

        try (FixArchiveScanner scanner = new FixArchiveScanner(context))
        {
            scanner.scan(
                configuration.libraryAeronChannel(),
                configuration.outboundLibraryStream(),
                fixMessageConsumer,
                false,
                DEFAULT_ARCHIVE_SCANNER_STREAM);

            assertThat(messages.toString(), messages, hasItems(
                Matchers.containsString("35=A\00149=acceptor\00156=initiator\00134=1"),
                Matchers.containsString("\001112=" + testReqIdPrefix)));
        }
    }
}
