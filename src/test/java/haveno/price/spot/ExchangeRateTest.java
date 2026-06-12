/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.price.spot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExchangeRateTest {

    private static final long TIMESTAMP_MILLIS = 1700000000123L;

    /**
     * The timestamp is kept in epoch milliseconds throughout, matching the pricenode's internal
     * clock and the unit the Haveno client uses for price staleness checks.
     */
    @Test
    public void getTimestamp_isMillis() {
        ExchangeRate rate = new ExchangeRate("XMR", "USD", 150.0, TIMESTAMP_MILLIS, "test");
        assertEquals(TIMESTAMP_MILLIS, rate.getTimestamp());
    }

    /**
     * The serialized JSON exposes the timestamp in milliseconds under "timestampMs". The legacy
     * "timestampSec" key is also emitted with the same millisecond value for backward
     * compatibility with older clients (it has always carried milliseconds despite its name),
     * until it can be removed in a future release.
     */
    @Test
    public void serializesTimestampInMillisUnderBothKeys() {
        ExchangeRate rate = new ExchangeRate("XMR", "USD", 150.0, TIMESTAMP_MILLIS, "test");
        JsonNode node = new ObjectMapper().valueToTree(rate);
        assertEquals(TIMESTAMP_MILLIS, node.get("timestampMs").asLong());
        assertEquals(TIMESTAMP_MILLIS, node.get("timestampSec").asLong(),
                "legacy timestampSec must keep carrying milliseconds for backward compatibility");
    }
}
