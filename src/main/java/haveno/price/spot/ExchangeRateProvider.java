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

import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.TradeCurrency;
import haveno.price.PriceProvider;
import haveno.price.util.GatedLogging;
import lombok.Getter;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.NotYetImplementedForExchangeException;
import org.knowm.xchange.service.marketdata.MarketDataService;
import org.knowm.xchange.service.marketdata.params.CurrencyPairsParam;
import org.knowm.xchange.service.marketdata.params.Params;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Abstract base class for providers of bitcoin {@link ExchangeRate} data. Implementations
 * are marked with the {@link org.springframework.stereotype.Component} annotation in
 * order to be discovered via classpath scanning. If multiple
 * {@link ExchangeRateProvider}s retrieve rates for the same currency, then the
 * {@link ExchangeRateService} will average them out and expose an aggregate rate.
 *
 * @see ExchangeRateService#getAllMarketPrices()
 */
public abstract class ExchangeRateProvider extends PriceProvider<Set<ExchangeRate>> {

    private static final long STALE_PRICE_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static Set<String> SUPPORTED_CRYPTO_CURRENCIES = new HashSet<>();
    private static Set<String> SUPPORTED_FIAT_CURRENCIES = new HashSet<>();
    private final Set<String> providerExclusionList = new HashSet<>();
    private final String name;
    private final String prefix;
    private final Environment env;
    @Getter
    private final GatedLogging gatedLogging = new GatedLogging();

    public ExchangeRateProvider(Environment env, String name, String prefix, Duration refreshInterval) {
        super(refreshInterval);
        this.name = name;
        this.prefix = prefix;
        this.env = env;
        String[] excludedByProvider =
                env.getProperty("haveno.price.currency.excludedByProvider", "")
                        .toUpperCase().trim().split("\\s*,\\s*");
        for (String s : excludedByProvider) {
            String[] splits = s.split(":");
            if (splits.length == 2 && splits[0].equalsIgnoreCase(name)) {
                providerExclusionList.add(splits[1]);
            }
        }
        if (providerExclusionList.size() > 0) {
            log.info("{} specific exclusion list={}", name, providerExclusionList);
        }
    }

    public Set<String> getSupportedFiatCurrencies() {
        if (SUPPORTED_FIAT_CURRENCIES.isEmpty()) {         // one-time initialization
            List<String> excludedFiatCurrencies =
                    Arrays.asList(env.getProperty("haveno.price.fiatcurrency.excluded", "")
                            .toUpperCase().trim().split("\\s*,\\s*"));
            String validatedExclusionList = excludedFiatCurrencies.stream()
                    .filter(ccy -> !ccy.isEmpty())
                    .filter(CurrencyUtil::isFiatCurrency)
                    .collect(Collectors.toList()).toString();
            SUPPORTED_FIAT_CURRENCIES = CurrencyUtil.getAllSortedFiatCurrencies().stream()
                    .map(TradeCurrency::getCode)
                    .filter(ccy -> !validatedExclusionList.contains(ccy.toUpperCase()))
                    .collect(Collectors.toSet());
            log.info("fiat currencies excluded: {}", validatedExclusionList);
            log.info("fiat currencies supported: {}", SUPPORTED_FIAT_CURRENCIES.size());
        }
        // filter out any provider specific ccy exclusions
        return SUPPORTED_FIAT_CURRENCIES.stream()
                .filter(ccy -> !providerExclusionList.contains(ccy.toUpperCase()))
                .collect(Collectors.toSet());
    }

    public Set<String> getSupportedCryptoCurrencies() {
        if (SUPPORTED_CRYPTO_CURRENCIES.isEmpty()) { // one-time initialization
            List<String> excludedCryptoCurrencies =
                    Arrays.asList(env.getProperty("haveno.price.cryptocurrency.excluded", "")
                            .toUpperCase().trim().split("\\s*,\\s*"));
            String validatedExclusionList = excludedCryptoCurrencies.stream()
                    .filter(ccy -> !ccy.isEmpty())
                    .filter(CurrencyUtil::isCryptoCurrency)
                    .collect(Collectors.toList()).toString();
            SUPPORTED_CRYPTO_CURRENCIES = CurrencyUtil.getAllSortedCryptoCurrencies().stream()
                    .map(TradeCurrency::getCode)
                    .filter(ccy -> !validatedExclusionList.contains(ccy.toUpperCase()))
                    .map(CurrencyUtil::getCurrencyCodeBase)
                    .collect(Collectors.toSet());
            SUPPORTED_CRYPTO_CURRENCIES.add("XMR"); // XMR is skipped because it's a base currency
            log.info("crypto currencies excluded: {}", validatedExclusionList);
            log.info("crypto currencies supported: {}", SUPPORTED_CRYPTO_CURRENCIES);
        }
        // filter out any provider specific ccy exclusions
        return SUPPORTED_CRYPTO_CURRENCIES.stream()
                .filter(ccy -> !providerExclusionList.contains(ccy.toUpperCase()))
                .collect(Collectors.toSet());
    }

    public String getName() {
        return name;
    }

    public String getPrefix() {
        return prefix;
    }

    public void maybeClearStaleRates() {
        if (get() == null) {
            return;
        }
        // a stale rate is older than the specified interval, except:
        // timestamp of 0L is used as special case re: CoinMarketCap and BitcoinAverage
        //   (https://github.com/haveno-network/haveno-pricenode/issues/23)
        long staleTimestamp = new Date().getTime() - STALE_PRICE_INTERVAL_MILLIS;
        Set<ExchangeRate> nonStaleRates = get().stream()
                    .filter(e -> e.getTimestamp() == 0L || e.getTimestamp() > staleTimestamp)
                    .collect(Collectors.toSet());
        long numberOriginalRates = get().size();
        if (numberOriginalRates > nonStaleRates.size()) {
            put(nonStaleRates);
            log.warn("{} {} stale rates removed, now {} rates",
                    getName(), numberOriginalRates, nonStaleRates.size());
        }
    }

    @Override
    protected void onRefresh() {
        get().stream()
                .filter(e -> "USD".equals(e.getCounterCurrency()) || "XMR".equals(e.getBaseCurrency()) || "ETH".equals(e.getBaseCurrency()) || "BCH".equals(e.getBaseCurrency()) || "USDT".equals(e.getBaseCurrency()))
                .forEach(e -> log.info("{}/{}: {}", e.getBaseCurrency(), e.getCounterCurrency(), e.getPrice()));
    }

    /**
     * @param exchangeClass Class of the {@link Exchange} for which the rates should be
     *                      polled
     * @return Exchange rates for Haveno-supported fiat currencies and cryptocurrencies in the
     * specified {@link Exchange}
     * @see CurrencyUtil#getAllSortedFiatCurrencies()
     * @see CurrencyUtil#getAllSortedCryptoCurrencies()
     * It must not pass exceptions up, instead return an empty set if there is a problem with the feed.
     * (otherwise PriceProvider would keep supplying stale rates).
     */
    protected Set<ExchangeRate> doGet(Class<? extends Exchange> exchangeClass) {
        try {
            return doGetInternal(exchangeClass);
        } catch (Exception e) {
            log.warn(e.toString());
        }
        return new HashSet<>();
    }

    private Set<ExchangeRate> doGetInternal(Class<? extends Exchange> exchangeClass) {
        Set<ExchangeRate> result = new HashSet<>();

        // Initialize XChange objects
        Exchange exchange = ExchangeFactory.INSTANCE.createExchange(exchangeClass.getName());
        MarketDataService marketDataService = exchange.getMarketDataService();

        // Retrieve all currency pairs supported by the exchange
        List<CurrencyPair> allCurrencyPairsOnExchange = exchange.getExchangeSymbols();

        // Find out which currency pairs we are interested in polling ("desired pairs")
        // This will be the intersection of:
        // 1) the pairs available on the exchange, and
        // 2) the pairs Haveno considers relevant / valid
        // This will result in two lists of desired pairs (fiat and alts)

        // Find the desired fiat pairs (pair format is CRYPTO-FIAT)
        List<CurrencyPair> desiredFiatPairs = allCurrencyPairsOnExchange.stream()
                .filter(cp -> cp.base.equals(Currency.BTC) || (cp.base.equals(Currency.XMR) && !cp.counter.equals(Currency.BTC)))
                .filter(cp -> getSupportedFiatCurrencies().contains(cp.counter.getCurrencyCode()) ||
                        // include also stablecoins, which are quoted fiat-like.. see below isInverted()
                        getSupportedCryptoCurrencies().contains(translateToHavenoCurrency(cp.counter.getCurrencyCode())))
                .collect(Collectors.toList());

        // Find the desired crypto pairs (pair format is CRYPTO-BTC)
        List<CurrencyPair> desiredCryptoPairs = allCurrencyPairsOnExchange.stream()
                .filter(cp -> cp.counter.equals(Currency.BTC))
                .filter(cp -> getSupportedCryptoCurrencies().contains(cp.base.getCurrencyCode()))
                .collect(Collectors.toList());

        // Retrieve in bulk all tickers offered by the exchange
        // The benefits of this approach (vs polling each ticker) are twofold:
        // 1) the polling of the exchange is faster (one HTTP call vs several)
        // 2) it's easier to stay below any API rate limits the exchange might have
        List<Ticker> tickersRetrievedFromExchange = new ArrayList<>();
        try {
            tickersRetrievedFromExchange = marketDataService.getTickers(new CurrencyPairsParam() {

                /**
                 * The {@link MarketDataService#getTickers(Params)} interface requires a
                 * {@link CurrencyPairsParam} argument when polling for tickers in bulk.
                 * This parameter is meant to indicate a list of currency pairs for which
                 * the tickers should be polled. However, the actual implementations for
                 * the different exchanges differ, for example:
                 * - some will ignore it (and retrieve all available tickers)
                 * - some will require it (and will fail if a null or empty list is given)
                 * - some will properly handle it
                 *
                 * We take a simplistic approach, namely:
                 * - for providers that require such a filter, specify one
                 * - for all others, do not specify one
                 *
                 * We make this distinction using
                 * {@link ExchangeRateProvider#requiresFilterDuringBulkTickerRetrieval}
                 *
                 * @return Filter (list of desired currency pairs) to be used during bulk
                 * ticker retrieval
                 */
                @Override
                public Collection<CurrencyPair> getCurrencyPairs() {
                    // If required by the exchange implementation, specify a filter
                    // (list of pairs which should be retrieved)
                    if (requiresFilterDuringBulkTickerRetrieval()) {
                        return Stream.of(desiredFiatPairs, desiredCryptoPairs)
                                .flatMap(Collection::stream)
                                .collect(Collectors.toList());
                    }

                    // Otherwise, specify an empty list, indicating that the API should
                    // simply return all available tickers
                    return Collections.emptyList();
                }
            });

            if (tickersRetrievedFromExchange.isEmpty()) {
                // If the bulk ticker retrieval went through, but no tickers were
                // retrieved, this is a strong indication that this specific exchange
                // needs a specific list of pairs given as argument, for bulk retrieval to
                // work. See requiresFilterDuringBulkTickerRetrieval()
                throw new IllegalArgumentException("No tickers retrieved, " +
                        "exchange requires explicit filter argument during bulk retrieval?");
            }
        } catch (NotYetImplementedForExchangeException e) {
            // Thrown when a provider has no marketDataService.getTickers() implementation
            // either because the exchange API does not provide it, or because it has not
            // been implemented yet in the knowm xchange library

            // In this case (retrieval of bulk tickers is not possible) retrieve the
            // tickers one by one
            List<Ticker> finalTickersRetrievedFromExchange = tickersRetrievedFromExchange;
            Stream.of(desiredFiatPairs, desiredCryptoPairs)
                    .flatMap(Collection::stream)
                    .forEach(cp -> {
                        try {

                            // This is done in a loop, and can therefore result in a burst
                            // of API calls. Some exchanges do not allow bursts
                            // A simplistic solution is to delay every call by 1 second
                            // TODO Switch to using a more elegant solution (per exchange)
                            // like ResilienceSpecification (needs knowm xchange libs v5)
                            if (getMarketDataCallDelay() > 0) {
                                Thread.sleep(getMarketDataCallDelay());
                            }

                            Ticker ticker = marketDataService.getTicker(cp);
                            finalTickersRetrievedFromExchange.add(ticker);

                        } catch (IOException | InterruptedException ioException) {
                            ioException.printStackTrace();
                            log.error("Could not query tickers for " + getName(), e);
                        }
                    });
        } catch (ExchangeException | // Errors reported by the exchange (rate limit, etc)
                IOException | // Errors while trying to connect to the API (timeouts, etc)
                // Potential error when integrating new exchange (hints that exchange
                // provider implementation needs to overwrite
                // requiresFilterDuringBulkTickerRetrieval() and have it return true )
                IllegalArgumentException e) {
            // Catch and handle all other possible exceptions
            // If there was a problem with polling this exchange, return right away,
            // since there are no results to parse and process
            log.error("Could not query tickers for provider " + getName(), e);
            return result;
        }

        // Create an ExchangeRate for each desired currency pair ticker that was retrieved
        Predicate<Ticker> isDesiredFiatPair = t -> desiredFiatPairs.contains(t.getCurrencyPair());
        Predicate<Ticker> isDesiredCryptoPair = t -> desiredCryptoPairs.contains(t.getCurrencyPair());
        Predicate<Ticker> isInverted =  t -> desiredFiatPairs.contains(t.getCurrencyPair()) &&
                getSupportedCryptoCurrencies().contains(translateToHavenoCurrency(t.getCurrencyPair().counter.getCurrencyCode()));
        tickersRetrievedFromExchange.stream()
                .filter(isDesiredFiatPair.or(isDesiredCryptoPair)) // Only consider desired pairs
                .forEach(t -> {

                    // skip if price not available
                    if (t.getLast() == null) return;

                    // create spot price for base and counter currencies
                    ExchangeRate rate = null;
                    BigDecimal last = t.getLast();
                    if (isInverted.test(t)) {
                        // Haveno price format currently expects all cryptocurrencies with BTC or XMR as the denominator
                        // most stable coins are quoted as fiat (DAI being an exception on SOME exchanges),
                        // they need have price inverted for Haveno client to handle them properly.
                        last = BigDecimal.valueOf(1.0).divide(last, 8, RoundingMode.HALF_UP);
                        log.info("{} isInverted, price translated from {} to {} for Haveno client.",
                                t.getCurrencyPair().base.getCurrencyCode() + "/" + t.getCurrencyPair().counter.getCurrencyCode(), t.getLast(), last);
                        rate = new ExchangeRate(
                            translateToHavenoCurrency(t.getCurrencyPair().counter.getCurrencyCode()),
                            translateToHavenoCurrency(t.getCurrencyPair().base.getCurrencyCode()),
                            last,
                            t.getTimestamp() == null ? new Date() : t.getTimestamp(), // some exchanges don't provide timestamps
                            this.getName()
                        );
                    } else {
                        rate = new ExchangeRate(
                            translateToHavenoCurrency(t.getCurrencyPair().base.getCurrencyCode()),
                            translateToHavenoCurrency(t.getCurrencyPair().counter.getCurrencyCode()),
                            last,
                            t.getTimestamp() == null ? new Date() : t.getTimestamp(), // some exchanges don't provide timestamps
                            this.getName()
                        );
                    }

                    // add rate to the result set
                    result.add(rate);
                });

        return result;
    }

    /**
     * Specifies optional delay between certain kind of API calls that can result in
     * bursts. We want to avoid bursts, because this can cause certain exchanges to
     * temporarily restrict access to the pricenode IP.
     *
     * @return Amount of milliseconds of delay between marketDataService.getTicker calls.
     * By default 0, but can be overwritten by each provider.
     */
    protected long getMarketDataCallDelay() {
        return 0;
    }

    /**
     * @return Whether or not the bulk retrieval of tickers from the exchange requires an
     * explicit filter (list of desired pairs) or not. If true, the
     * {@link MarketDataService#getTickers(Params)} call will be constructed and given as
     * argument, which acts as a filter indicating for which pairs the ticker should be
     * retrieved. If false, {@link MarketDataService#getTickers(Params)} will be called
     * with an empty argument, indicating that the API should simply return all available
     * tickers on the exchange
     */
    protected boolean requiresFilterDuringBulkTickerRetrieval() {
        return false;
    }

    private String translateToHavenoCurrency(String exchangeCurrency) {
        return exchangeCurrency; // skip mapping for usdt/usdc so they're universal

        // map between USDT and USDT-ERC20
        //return exchangeCurrency.equalsIgnoreCase("USDT") ? "USDT-ERC20" : exchangeCurrency;

        // map between USDC and USDC-ERC20
        //return exchangeCurrency.equalsIgnoreCase("USDC") ? "USDC-ERC20" : exchangeCurrency;
    }
}
