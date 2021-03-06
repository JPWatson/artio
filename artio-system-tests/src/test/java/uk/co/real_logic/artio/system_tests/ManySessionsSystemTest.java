/*
 * Copyright 2019 Adaptive Financial Consulting Ltd.
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

import org.junit.Before;
import org.junit.Test;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.LowResourceEngineScheduler;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.validation.AuthenticationStrategy;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static java.util.Collections.singletonList;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class ManySessionsSystemTest extends AbstractGatewayToGatewaySystemTest
{
    private static final int NUMBER_OF_SESSIONS = 10;

    private final FakeConnectHandler fakeConnectHandler = new FakeConnectHandler();

    @Before
    public void launch()
    {
        delete(ACCEPTOR_LOGS);

        mediaDriver = launchMediaDriver();

        final EngineConfiguration configuration = new EngineConfiguration();
        final AuthenticationStrategy authenticationStrategy = AuthenticationStrategy.none();
        configuration.authenticationStrategy(authenticationStrategy);

        acceptingEngine = FixEngine.launch(
            configuration
                .bindTo("localhost", port)
                .libraryAeronChannel(IPC_CHANNEL)
                .monitoringFile(acceptorMonitoringFile("engineCounters"))
                .logFileDir(ACCEPTOR_LOGS)
                .scheduler(new LowResourceEngineScheduler()));

        initiatingEngine = launchInitiatingEngine(libraryAeronPort);

        final LibraryConfiguration acceptingLibraryConfig = new LibraryConfiguration()
            .sessionExistsHandler(acceptingHandler)
            .sessionAcquireHandler(acceptingHandler)
            .sentPositionHandler(acceptingHandler)
            .libraryAeronChannels(singletonList(IPC_CHANNEL))
            .libraryName("accepting");

        acceptingLibraryConfig.libraryConnectHandler(fakeConnectHandler);
        acceptingLibrary = connect(acceptingLibraryConfig);
        initiatingLibrary = newInitiatingLibrary(libraryAeronPort, initiatingHandler);
        testSystem = new TestSystem(acceptingLibrary, initiatingLibrary);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldConnectManySessions()
    {
        final Reply<Session>[] replies = IntStream.range(0, NUMBER_OF_SESSIONS)
            .mapToObj(i -> initiate(initiatingLibrary, port, initId(i), accId(i)))
            .toArray(Reply[]::new);

        testSystem.awaitCompletedReplies(replies);

        final List<Session> sessions = Stream.of(replies)
            .map(Reply::resultIfPresent)
            .collect(Collectors.toList());

        sessions.forEach(this::messagesCanBeExchanged);
    }

    private static String accId(final int i)
    {
        return ACCEPTOR_ID + i;
    }

    private static String initId(final int i)
    {
        return INITIATOR_ID + i;
    }
}
