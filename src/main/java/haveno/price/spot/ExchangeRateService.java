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

import haveno.common.util.Tuple2;
import haveno.core.locale.CurrencyUtil;
import haveno.core.util.InlierUtil;
import haveno.price.util.GatedLogging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * High-level {@link ExchangeRate} data operations.
 */
@Service
@Slf4j
class ExchangeRateService {
    private final Environment env;
    private final List<ExchangeRateProvider> providers;
    private final List<ExchangeRateTransformer> transformers;
    private final GatedLogging gatedLogging = new GatedLogging();

    /**
     * Construct an {@link ExchangeRateService} with a list of all
     * {@link ExchangeRateProvider} implementations discovered via classpath scanning.
     *
     * @param providers    all {@link ExchangeRateProvider} implementations in ascending
     *                     order of precedence
     * @param transformers all {@link ExchangeRateTransformer} implementations
     */
    public ExchangeRateService(Environment env,
                               List<ExchangeRateProvider> providers,
                               List<ExchangeRateTransformer> transformers) {
        this.env = env;
        this.providers = providers;
        this.transformers = transformers;
    }

    public Map<String, Object> getAllMarketPrices() {

        // get aggregate exchange rates for xmr
        List<ExchangeRate> aggregateExchangeRates = getAggregateExchangeRatesXmr();
        aggregateExchangeRates.sort(Comparator.comparing(ExchangeRate::getBaseCurrency).thenComparing(ExchangeRate::getCounterCurrency));

        // get metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        providers.forEach(p -> {
            p.maybeClearStaleRates();
            // Specific metadata fields for specific providers are expected by the client,
            // mostly for historical reasons
            // Therefore, add metadata fields for all known providers
            // Rates are encapsulated in the "data" map below
            metadata.putAll(getMetadata(p));
        });

        // return result
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(metadata);
        result.put("data", aggregateExchangeRates);
        return result;
    }

    private List<ExchangeRate> getAggregateExchangeRatesXmr() {

        // fetch all aggregate rates
        Map<String, Map<String, ExchangeRate>> aggregateRates = getAggregateExchangeRates();

        // translate each rate to xmr
        Map<String, Map<String, ExchangeRate>> xmrAggregateRates = new HashMap<>();
        aggregateRates.values().stream()
                .flatMap(m -> m.values().stream())
                .forEach(r -> {
                    ExchangeRate rate = translateExchangeRateToXmr(r, aggregateRates);
                    if (rate == null) return;
                    if (!xmrAggregateRates.containsKey(rate.getBaseCurrency())) xmrAggregateRates.put(rate.getBaseCurrency(), new HashMap<String, ExchangeRate>());
                    xmrAggregateRates.get(rate.getBaseCurrency()).put(rate.getCounterCurrency(), rate);
                });

        // return xmr rates
        return xmrAggregateRates.values().stream()
                .flatMap(m -> m.values().stream())
                .collect(Collectors.toList());
    }

    private ExchangeRate translateExchangeRateToXmr(ExchangeRate rate, Map<String, Map<String, ExchangeRate>> aggregateRates) {
        String BTC = "BTC";
        String XMR = "XMR";
        String USD = "USD";

        // invert XMR/BTC rate because XMR is counter currency for crypto pairs
        if (rate.getBaseCurrency().equals(XMR) && rate.getCounterCurrency().equals(BTC)) {
            ExchangeRate xmrRate = aggregateRates.get(XMR).get(rate.getCounterCurrency());
            BigDecimal rateBD = new BigDecimal(xmrRate.getPrice());
            BigDecimal inverseRate = (rateBD.compareTo(BigDecimal.ZERO) > 0) ? BigDecimal.ONE.divide(rateBD, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            return new ExchangeRate(
                    BTC,
                    XMR,
                    inverseRate.doubleValue(),
                    rate.getTimestamp(),
                    rate.getProvider());
        }

        // use direct rate if available
        if (rate.getBaseCurrency().equals(XMR) || rate.getCounterCurrency().equals(XMR)) return rate;

        // translate to xmr
        ExchangeRate xmrBtcRate = aggregateRates.containsKey(XMR) ? aggregateRates.get(XMR).get(BTC) : null;
        ExchangeRate xmrUsdRate = aggregateRates.containsKey(XMR) ? aggregateRates.get(XMR).get(USD) : null;
        boolean isCryptoPair = CurrencyUtil.isCryptoCurrency(rate.getCounterCurrency());
        if (isCryptoPair) {
            ExchangeRate cryptoUsdRate = aggregateRates.get(rate.getBaseCurrency()).get(USD);
            if (cryptoUsdRate == null) {

                // convert xmr to btc to crypto
                ExchangeRate cryptoBtcRate = aggregateRates.get(rate.getBaseCurrency()).get(BTC);
                if (cryptoBtcRate == null) {
                    log.warn("No {}/BTC rate available", rate.getBaseCurrency());
                    return null;
                }
                if (xmrBtcRate == null) {
                    log.warn("No XMR/BTC rate available");
                    return null;
                }
                return new ExchangeRate(
                        rate.getBaseCurrency(),
                        XMR,
                        cryptoBtcRate.getPrice() / xmrBtcRate.getPrice(),
                        xmrBtcRate.getTimestamp(),
                        xmrBtcRate.getProvider()
                );
            } else {
                
                // convert xmr to usd to crypto
                if (xmrUsdRate == null) {
                    log.warn("No XMR/USD rate available");
                    return null;
                }
                return new ExchangeRate(
                    rate.getBaseCurrency(),
                    XMR,
                    cryptoUsdRate.getPrice() / xmrUsdRate.getPrice(),
                    xmrBtcRate.getTimestamp(),
                    xmrBtcRate.getProvider()
                );
            }
        } else {
            
            // convert xmr to btc to fiat
            ExchangeRate btcFiatRate = aggregateRates.get(BTC).get(rate.getCounterCurrency());
            if (btcFiatRate == null) {
                log.warn("No BTC/{} rate available", rate.getCounterCurrency());
                return null;
            }
            if (xmrBtcRate == null) {
                log.warn("No XMR/BTC rate available");
                return null;
            }
            return new ExchangeRate(
                    XMR,
                    rate.getCounterCurrency(),
                    xmrBtcRate.getPrice() * btcFiatRate.getPrice(),
                    btcFiatRate.getTimestamp(),
                    xmrBtcRate.getProvider()
            );
        }
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
        boolean maybeLogDetails = gatedLogging.gatingOperation();
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
                    double priceAvg = priceAverageWithOutliersRemoved(exchangeRateList, baseCurrencyCode + "/" + counterCurrencyCode, maybeLogDetails);
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

    private double priceAverageWithOutliersRemoved(
            List<ExchangeRate> exchangeRateList, String contextInfo, boolean logOutliers) {
        final List<Double> yValues = exchangeRateList.stream().
                mapToDouble(ExchangeRate::getPrice).boxed().collect(Collectors.toList());
        Tuple2<Double, Double> tuple = InlierUtil.findInlierRange(yValues, 0, getOutlierStdDeviation());
        double lowerBound = tuple.first;
        double upperBound = tuple.second;
        final List<ExchangeRate> filteredPrices = exchangeRateList.stream()
                .filter(e -> e.getPrice() >= lowerBound)
                .filter(e -> e.getPrice() <= upperBound)
                .collect(Collectors.toList());

        if (filteredPrices.size() < 1) {
            log.error("{}: could not filter, revert to plain average. lowerBound={}, upperBound={}, stdDev={}, yValues={}",
                    contextInfo, lowerBound, upperBound, getOutlierStdDeviation(), yValues);
            return exchangeRateList.stream().mapToDouble(ExchangeRate::getPrice).average().getAsDouble();
        }

        OptionalDouble opt = filteredPrices.stream().mapToDouble(ExchangeRate::getPrice).average();
        // List size > 1, so opt is always set
        double priceAvg = opt.orElseThrow(IllegalStateException::new);

        // log the outlier prices which were removed from the average, if any.
        if (logOutliers) {
            for (ExchangeRate badRate : exchangeRateList.stream()
                    .filter(e -> !filteredPrices.contains(e))
                    .collect(Collectors.toList())) {
                log.info("{} {} outlier price removed:{}, lower/upper bounds:{}/{}, consensus price:{}",
                        badRate.getProvider(),
                        badRate.getBaseCurrency() + "/" + badRate.getCounterCurrency(),
                        badRate.getPrice(),
                        lowerBound,
                        upperBound,
                        priceAvg);
            }
        }
        return priceAvg;
    }

    private double getOutlierStdDeviation() {
        return Double.parseDouble(env.getProperty("haveno.price.outlierStdDeviation", "1.1"));
    }

    /**
     * @return All {@link ExchangeRate}s from all providers.
     */
    private Map<String, Map<String, List<ExchangeRate>>> getAllExchangeRates() {
        Map<String, Map<String, List<ExchangeRate>>> exchangeRates = new HashMap<>();
        for (ExchangeRateProvider p : providers) {
            Set<ExchangeRate> providerRates = p.get();
            if (providerRates == null) continue;
            for (ExchangeRate providerRate : providerRates) {
                if (!exchangeRates.containsKey(providerRate.getBaseCurrency())) exchangeRates.put(providerRate.getBaseCurrency(), new HashMap<String, List<ExchangeRate>>());
                Map<String, List<ExchangeRate>> baseMap = exchangeRates.get(providerRate.getBaseCurrency());

                String currencyCode = providerRate.getCounterCurrency();

                if (!baseMap.containsKey(providerRate.getCounterCurrency())) baseMap.put(providerRate.getCounterCurrency(), new ArrayList<ExchangeRate>());
                List<ExchangeRate> rates = baseMap.get(providerRate.getCounterCurrency());
                rates.add(providerRate);
            }
        }
        return exchangeRates;
    }

    private Map<String, Object> getMetadata(ExchangeRateProvider provider) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        // In case a provider is not available we still want to deliver the data of the
        // other providers, so we catch a possible exception and leave timestamp at 0. The
        // Haveno app will check if the timestamp is in a tolerance window and if it is too
        // old it will show that the price is not available.
        long timestamp = 0;
        Set<ExchangeRate> exchangeRates = provider.get();
        try {
            if (exchangeRates != null) {
                timestamp = getTimestamp(provider, exchangeRates);
            }
        } catch (Throwable t) {
            log.error(t.toString());
            if (log.isDebugEnabled())
                t.printStackTrace();
        }

        String prefix = provider.getPrefix();
        metadata.put(prefix + "Ts", timestamp);
        metadata.put(prefix + "Count", exchangeRates == null ? 0 : exchangeRates.size());

        return metadata;
    }

    private long getTimestamp(ExchangeRateProvider provider, Set<ExchangeRate> exchangeRates) {
        return exchangeRates.stream()
                .filter(e -> e.getProvider().startsWith(provider.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No exchange rate data found for " + provider.getName()))
                .getTimestamp();
    }
}
