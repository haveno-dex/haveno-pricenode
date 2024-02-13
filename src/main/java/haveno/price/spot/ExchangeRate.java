/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

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

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;

/**
 * A value object representing the spot price for a given base and counter currency at a given
 * time as reported by a given provider.
 */
public class ExchangeRate {

    private final String baseCurrency;
    private final String counterCurrency;
    private final double price;
    private final long timestamp;
    private final String provider;

    public ExchangeRate(String baseCurrency, String counterCurrency, BigDecimal price, Date timestamp, String provider) {
        this(
                baseCurrency,
                counterCurrency,
                price.doubleValue(),
                timestamp.getTime(),
                provider
        );
    }

    public ExchangeRate(String baseCurrency, String counterCurrency, double price, long timestamp, String provider) {
        this.baseCurrency = baseCurrency;
        this.counterCurrency = counterCurrency;
        this.price = price;
        this.timestamp = timestamp;
        this.provider = provider;
    }

    @JsonProperty(value = "baseCurrencyCode", index = 1)
    public String getBaseCurrency() {
        return baseCurrency;
    }

    @JsonProperty(value = "counterCurrencyCode", index = 2)
    public String getCounterCurrency() {
        return counterCurrency;
    }

    @JsonProperty(value = "price", index = 3)
    public double getPrice() {
        return this.price;
    }

    @JsonProperty(value = "timestampSec", index = 4)
    public long getTimestamp() {
        return this.timestamp;
    }

    @JsonProperty(value = "provider", index = 5)
    public String getProvider() {
        return provider;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExchangeRate exchangeRate = (ExchangeRate) o;
        return Double.compare(exchangeRate.price, price) == 0 &&
                timestamp == exchangeRate.timestamp &&
                Objects.equals(baseCurrency, exchangeRate.baseCurrency) &&
                Objects.equals(counterCurrency, exchangeRate.counterCurrency) &&
                Objects.equals(provider, exchangeRate.provider);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseCurrency, counterCurrency, price, timestamp, provider);
    }

    @Override
    public String toString() {
        return "ExchangeRate{" +
                "baseCurrency='" + baseCurrency + '\'' +
                ", counterCurrency='" + counterCurrency + '\'' +
                ", price=" + price +
                ", timestamp=" + timestamp +
                ", provider=" + provider +
                '}';
    }
}
