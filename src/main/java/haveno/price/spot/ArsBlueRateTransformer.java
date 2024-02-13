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

package haveno.price.spot;

import haveno.price.spot.providers.BlueRateProvider;
import haveno.price.util.bluelytics.ArsBlueMarketGapProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalDouble;

@Component
public class ArsBlueRateTransformer implements ExchangeRateTransformer {
    private final ArsBlueMarketGapProvider blueMarketGapProvider;

    public ArsBlueRateTransformer(ArsBlueMarketGapProvider blueMarketGapProvider) {
        this.blueMarketGapProvider = blueMarketGapProvider;
    }

    @Override
    public Optional<ExchangeRate> apply(ExchangeRateProvider provider, ExchangeRate originalExchangeRate) {
        if (provider instanceof BlueRateProvider) {
            return Optional.of(originalExchangeRate);
        }

        OptionalDouble sellGapMultiplier = blueMarketGapProvider.get();
        if (sellGapMultiplier.isEmpty()) {
            return Optional.empty();
        }

        double blueRate = originalExchangeRate.getPrice() * sellGapMultiplier.getAsDouble();

        ExchangeRate newExchangeRate = new ExchangeRate(
                originalExchangeRate.getBaseCurrency(),
                originalExchangeRate.getCounterCurrency(),
                blueRate,
                originalExchangeRate.getTimestamp(),
                originalExchangeRate.getProvider()
        );

        provider.getGatedLogging().maybeLogInfo(String.format("%s transformed from %s to %s",
                    originalExchangeRate.getBaseCurrency() + "/" + originalExchangeRate.getCounterCurrency(), originalExchangeRate.getPrice(), blueRate));

        return Optional.of(newExchangeRate);
    }

    @Override
    public String supportedCurrency() {
        return "ARS";
    }
}
