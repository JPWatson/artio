/*
 * Copyright 2019 Monotonic Ltd.
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
package uk.co.real_logic.artio.dictionary;

import org.agrona.LangUtil;
import uk.co.real_logic.artio.builder.*;
import uk.co.real_logic.artio.decoder.*;

public interface FixDictionary
{
    static FixDictionary of(Class<? extends FixDictionary> fixDictionaryType)
    {
        try
        {
            return fixDictionaryType.newInstance();
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            LangUtil.rethrowUnchecked(e);
            throw new RuntimeException();  // Never invoked
        }
    }

    String beginString();

    AbstractLogonEncoder makeLogonEncoder();

    AbstractLogoutEncoder makeLogoutEncoder();

    AbstractHeartbeatEncoder makeHeartbeatEncoder();

    AbstractRejectEncoder makeRejectEncoder();

    AbstractTestRequestEncoder makeTestRequestEncoder();

    AbstractSequenceResetEncoder makeSequenceResetEncoder();

    AbstractLogonDecoder makeLogonDecoder();

    AbstractLogoutDecoder makeLogoutDecoder();

    AbstractRejectDecoder makeRejectDecoder();

    AbstractTestRequestDecoder makeTestRequestDecoder();

    AbstractSequenceResetDecoder makeSequenceResetDecoder();

    AbstractHeartbeatDecoder makeHeartbeatDecoder();

    SessionHeaderDecoder makeHeaderDecoder();
}
