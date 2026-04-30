# Testing RiskPilot-2026

## Project Overview
Spring Boot quantitative trading system (shadow mode) that connects to Angel One broker API via WebSocket for live NIFTY tick data. Uses H2 in-memory database, trap-detection strategy, and simulated execution.

## Build & Test Commands

```bash
# Navigate to the Maven project root
cd riskpilot/

# Compile (fast check for syntax/import errors)
./mvnw compile -q

# Run ALL tests (expect pre-existing failures — see below)
./mvnw test

# Run only the passing unit tests (no Spring context needed)
./mvnw test -Dtest=TradingSessionServiceTest

# Run a specific test class
./mvnw test -Dtest=YourTestClassName
```

## Credential Requirements

### Angel One Broker API (Required for app startup)
The app requires these environment variables to start:
- `ANGELONE_API_KEY`
- `ANGELONE_CLIENT_CODE`
- `ANGELONE_PASSWORD`
- `ANGELONE_TOTP_SECRET`

Without these, `AngelTickStreamClient.init()` throws `ANGEL_CREDENTIALS_MISSING` and the Spring context fails to load. These credentials are configured in the Render deployment environment.

### Devin Secrets Needed
- None required for compilation and unit testing
- Angel One credentials would be needed for full E2E testing (currently only in Render env vars)

## Pre-existing Test Failures
The following tests fail on `main` due to missing Angel One credentials in CI/local environments:
- `RiskpilotApplicationTests` (1 error) — Spring context fails to load
- `TradingControllerTest` (8 errors) — Same Spring context failure

Total: 9 pre-existing errors. These are NOT caused by code changes.

## Testing Without Credentials
Since the app cannot start without Angel One credentials, use these approaches:

1. **Compilation verification**: `./mvnw compile -q` — catches syntax, import, and type errors
2. **Passing unit tests**: `./mvnw test -Dtest=TradingSessionServiceTest` — tests service logic with mocks
3. **Targeted verification tests**: Write JUnit tests that don't need Spring context:
   - Use `@ExtendWith(MockitoExtension.class)` for mocked dependencies
   - Instantiate POJOs and engines directly (e.g., `KillSwitchEngine`, `Candle`, `RiskPilotProperties`)
   - Use Java reflection to verify method modifiers (e.g., `synchronized`)
4. **Code grep verification**: Search for removed/added patterns in source files

## Key Architecture Notes
- **Config**: `RiskPilotProperties.java` — central config, all values overridable via env vars
- **Mode**: Default is `SHADOW`. `StrictValidationService.validateSystem()` blocks `LIVE` mode at startup.
- **Trade gates**: `RiskGateEngine.evaluateEntry()` — checks kill switch, session, feed, regime, volatility, slippage, latency, daily loss, drawdown, entry window, time phase
- **Execution**: `ShadowExecutionEngine` — processes ticks and candles, no real broker orders
- **Kill switch**: `KillSwitchEngine` — checks `KILL_SWITCH.flag` file, cached via `@Scheduled` (2s poll)
- **Candle aggregation**: `CandleAggregator` — builds 5-min candles from ticks, drops late/out-of-order ticks
- **Signal generation**: `TrapEngine.detectTrap()` — breakout-trap detection on candle history

## Port & Profiles
- **Port**: 8080 (default)
- **Dev profile**: `application-dev.yml` — H2 in-memory, debug logging, `startup-fail-fast: false`
- **Prod profile**: `application-prod.yml` — H2 console disabled, limited actuator endpoints
- **Active profile**: Set via `SPRING_PROFILES_ACTIVE` env var (defaults to `dev`)

## Common Test Patterns

### Verifying a gate/validation was removed
```bash
# Search for the removed pattern in the relevant file
grep -c 'PATTERN_NAME' path/to/File.java
# Expected: 0 matches
```

### Verifying a method is synchronized
```java
Method method = ClassName.class.getDeclaredMethod("methodName", paramTypes...);
assertTrue(Modifier.isSynchronized(method.getModifiers()));
```

### Verifying exception is thrown (not silently swallowed)
```java
assertThrows(Exception.class, () -> objectWithBadInput.methodThatShouldThrow());
```

### Testing KillSwitchEngine caching
```java
KillSwitchEngine engine = new KillSwitchEngine();
assertFalse(engine.isKillSwitchTriggered()); // no file
Files.writeString(Path.of("KILL_SWITCH.flag"), "reason");
engine.refreshKillSwitchCache(); // manual trigger
assertTrue(engine.isKillSwitchTriggered());
Files.deleteIfExists(Path.of("KILL_SWITCH.flag"));
```

## Deployment
The app is deployed on Render. Angel One credentials are configured as Render environment variables.
