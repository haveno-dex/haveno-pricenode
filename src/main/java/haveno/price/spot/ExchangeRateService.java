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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import haveno.core.locale.CurrencyUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * High-level {@link ExchangeRate} data operations.
 */
@Service
class ExchangeRateService {
    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    private final List<ExchangeRateProvider> providers;

    /**
     * Construct an {@link ExchangeRateService} with a list of all
     * {@link ExchangeRateProvider} implementations discovered via classpath scanning.
     *
     * @param providers all {@link ExchangeRateProvider} implementations in ascending
     *                  order of precedence
     */
    public ExchangeRateService(List<ExchangeRateProvider> providers) {
        this.providers = providers;
    }

    public Map<String, Object> getAllMarketPrices() {

        // get aggregate exchange rates for xmr
        List<ExchangeRate> aggregateExchangeRates = getAggregateExchangeRatesXmr();
        aggregateExchangeRates.sort(Comparator.comparing(ExchangeRate::getBaseCurrency).thenComparing(ExchangeRate::getCounterCurrency));

        // get metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        providers.forEach(p -> {
            if (p.get() == null) return;
            Set<ExchangeRate> exchangeRates = p.get();

            // Specific metadata fields for specific providers are expected by the client,
            // mostly for historical reasons
            // Therefore, add metadata fields for all known providers
            // Rates are encapsulated in the "data" map below
            metadata.putAll(getMetadata(p, exchangeRates));
        });

        // return result
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(metadata);
        result.put("data", aggregateExchangeRates);
        return result;
    }

    private List<ExchangeRate> getAggregateExchangeRatesXmr() {
        String BTC = "BTC";
        String XMR = "XMR";
        String USD = "USD";

        // fetch all aggregate rates
        Map<String, Map<String, ExchangeRate>> aggregateRates = getAggregateExchangeRates();

        // get all currencies to translate xmr rates
        Set<String> currencies = new HashSet<String>(aggregateRates.keySet());
        for (String baseCurrency : aggregateRates.keySet()) currencies.addAll(aggregateRates.get(baseCurrency).keySet());
        currencies.remove(XMR);

        // translate xmr rates
        List<ExchangeRate> xmrRates = new ArrayList<ExchangeRate>();
        if (!aggregateRates.containsKey(XMR)) return xmrRates;
        ExchangeRate xmrBtcRate = aggregateRates.get(XMR).get(BTC);
        ExchangeRate xmrUsdRate = aggregateRates.get(XMR).get(USD);
        for (String currency : currencies) {

            // use direct rate if available
            if (aggregateRates.get(XMR).containsKey(currency)) {

                // invert rate if btc counter currency
                ExchangeRate rate = aggregateRates.get(XMR).get(currency);
                if (currency.equals(BTC)) {
                    BigDecimal rateBD = new BigDecimal(rate.getPrice());
                    BigDecimal inverseRate = (rateBD.compareTo(BigDecimal.ZERO) > 0) ? BigDecimal.ONE.divide(rateBD, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                    xmrRates.add(new ExchangeRate(
                            BTC,
                            XMR,
                            inverseRate.doubleValue(),
                            rate.getTimestamp(),
                            rate.getProvider()
                    ));
                } else {
                    xmrRates.add(rate);
                }
            } else {
                if (CurrencyUtil.isFiatCurrency(currency)) {

                    // convert xmr to btc to fiat
                    ExchangeRate btcFiatRate = aggregateRates.get(BTC).get(currency);
                    if (btcFiatRate == null) {
                        log.warn("No BTC/{} rate available", currency);
                        continue;
                    }
                    xmrRates.add(new ExchangeRate(
                            XMR,
                            currency,
                            xmrBtcRate.getPrice() * btcFiatRate.getPrice(),
                            btcFiatRate.getTimestamp(),
                            xmrBtcRate.getProvider()
                    ));
                } else if (CurrencyUtil.isCryptoCurrency(currency)) {
                    if (!aggregateRates.containsKey(currency)) {
                        log.warn("No exchange rate found for crypto: {}", currency);
                        continue;
                    }
                    ExchangeRate cryptoUsdRate = aggregateRates.get(currency).get(USD);
                    if (cryptoUsdRate == null) {

                        // convert xmr to btc to crypto
                        ExchangeRate cryptoBtcRate = aggregateRates.get(currency).get(BTC);
                        if (cryptoBtcRate == null) {
                            log.warn("No {}/BTC rate available", currency);
                            continue;
                        }
                        xmrRates.add(new ExchangeRate(
                                currency,
                                XMR,
                                cryptoBtcRate.getPrice() / xmrBtcRate.getPrice(),
                                xmrBtcRate.getTimestamp(),
                                xmrBtcRate.getProvider()
                        ));
                    } else {

                        // convert xmr to usd to crypto
                        xmrRates.add(new ExchangeRate(
                                currency,
                                XMR,
                                cryptoUsdRate.getPrice() / xmrUsdRate.getPrice(),
                                xmrBtcRate.getTimestamp(),
                                xmrBtcRate.getProvider()
                        ));
                    }
                } else {
                    log.warn("Currency is neither fiat nor crypto: " + currency);
                    continue;
                }
            }
        }

        return xmrRates;
    }

    /**
     * For each currency, create an aggregate {@link ExchangeRate} based on the currency's
     * rates from all providers. If multiple providers have rates for the currency, then
     * aggregate price = average of retrieved prices. If a single provider has rates for
     * the currency, then aggregate price = the rate from that provider.
     * 
     * @return all aggregate {@link ExchangeRate}s
     */
    private Map<String, Map<String, ExchangeRate>> getAggregateExchangeRates() {

        // fetch all exchange rates
        Map<String, Map<String, List<ExchangeRate>>> exchangeRates = getAllExchangeRates();

        // aggregate exchange rates
        Map<String, Map<String, ExchangeRate>> aggregateRates = new HashMap<>();
        exchangeRates.forEach((baseCurrencyCode, counterCurrencyMap) -> {
            counterCurrencyMap.forEach((counterCurrencyCode, exchangeRateList) -> {

                // skip if no rates
                if (exchangeRateList.isEmpty()) return;

                // get aggregate rate
                ExchangeRate aggregateRate;
                if (exchangeRateList.size() == 1) aggregateRate = exchangeRateList.get(0);
                else {
                    OptionalDouble opt = exchangeRateList.stream().mapToDouble(ExchangeRate::getPrice).average();
                    double priceAvg = opt.orElseThrow(IllegalStateException::new);
                    aggregateRate = new ExchangeRate(
                            baseCurrencyCode,
                            counterCurrencyCode,
                            BigDecimal.valueOf(priceAvg),
                            new Date(),
                            "Haveno-Aggregate");
                }

                // put aggregate rate
                if (!aggregateRates.containsKey(baseCurrencyCode)) aggregateRates.put(baseCurrencyCode, new HashMap<String, ExchangeRate>());
                aggregateRates.get(baseCurrencyCode).put(counterCurrencyCode, aggregateRate);
            });
        });
        return aggregateRates;
    }

    /**
     * @return All {@link ExchangeRate}s from all providers.
     */
    private Map<String, Map<String, List<ExchangeRate>>> getAllExchangeRates() {
        Map<String, Map<String, List<ExchangeRate>>> exchangeRates = new HashMap<>();
        for (ExchangeRateProvider p : providers) {

            // get provider rates
            Set<ExchangeRate> providerRates = p.get();
            if (providerRates == null) continue;

            // add to map
            for (ExchangeRate providerRate : providerRates) {
                if (!exchangeRates.containsKey(providerRate.getBaseCurrency())) exchangeRates.put(providerRate.getBaseCurrency(), new HashMap<String, List<ExchangeRate>>());
                Map<String, List<ExchangeRate>> baseMap = exchangeRates.get(providerRate.getBaseCurrency());
                if (!baseMap.containsKey(providerRate.getCounterCurrency())) baseMap.put(providerRate.getCounterCurrency(), new ArrayList<ExchangeRate>());
                List<ExchangeRate> rates = baseMap.get(providerRate.getCounterCurrency());
                rates.add(providerRate);
            }
        }
        return exchangeRates;
    }

    private Map<String, Object> getMetadata(ExchangeRateProvider provider, Set<ExchangeRate> exchangeRates) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        // In case a provider is not available we still want to deliver the data of the
        // other providers, so we catch a possible exception and leave timestamp at 0. The
        // Haveno app will check if the timestamp is in a tolerance window and if it is too
        // old it will show that the price is not available.
        long timestamp = 0;
        try {
            timestamp = getTimestamp(provider, exchangeRates);
        } catch (Throwable t) {
            log.error(t.toString());
            if (log.isDebugEnabled())
                t.printStackTrace();
        }

        String prefix = provider.getPrefix();
        metadata.put(prefix + "Ts", timestamp);
        metadata.put(prefix + "Count", exchangeRates.size());

        return metadata;
    }

    private long getTimestamp(ExchangeRateProvider provider, Set<ExchangeRate> exchangeRates) {
        return exchangeRates.stream()
                .filter(e -> provider.getName().equals(e.getProvider()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No exchange rate data found for " + provider.getName()))
                .getTimestamp();
    }
}
