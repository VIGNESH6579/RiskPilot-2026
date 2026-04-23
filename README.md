# RiskPilot-2026: Advanced Algorithmic Trading System

A sophisticated algorithmic trading system designed for Bank Nifty and Nifty index trading with shadow execution capabilities, real-time monitoring, and comprehensive risk management.

## 🚀 Features

### Core Trading Engine
- **Shadow Execution**: Air-gapped trading simulation without real market execution
- **Real-time Risk Management**: Multi-layered risk controls with dynamic position sizing
- **Advanced Signal Generation**: Trap Engine with OR (Opening Range) detection
- **Session Management**: Complete trading session lifecycle management
- **Performance Analytics**: Real-time P&L tracking and performance metrics

### Market Data Integration
- **Angel One API Integration**: Real-time market data and authentication
- **Candle Aggregation**: Tick-to-candle conversion with multiple timeframes
- **Heartbeat Monitoring**: System health and connection monitoring
- **VIX Service**: Volatility-based regime detection

### Monitoring & Observability
- **WebSocket Observer**: Real-time trade monitoring via Python observer
- **Spring Actuator**: Health checks and metrics endpoints
- **Comprehensive Logging**: Structured logging with different levels
- **Alert System**: ntfy integration for trade notifications

### Risk Management
- **Dynamic Stop Loss**: Trailing stop loss with MAE protection
- **TP1/TP2 Logic**: Partial profit booking with runner management
- **Position Sizing**: Risk-based position calculation
- **Time-based Filters**: Session time and volatility filters

## 🏗️ Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Frontend     │    │  Trading Engine │    │   Observer     │
│   (HTML/JS)   │◄──►│  (Spring Boot) │◄──►│   (Python)     │
└─────────────────┘    └──────────────────┘    └─────────────────┘
                                │
                                ▼
                       ┌──────────────────┐
                       │   Database      │
                       │ (H2/MySQL)    │
                       └──────────────────┘
```

## 📋 Prerequisites

- **Java 17+**
- **Maven 3.8+**
- **Python 3.8+**
- **Docker & Docker Compose** (optional)
- **MySQL 8.0+** (for production)

## 🛠️ Installation

### 1. Clone the Repository
```bash
git clone https://github.com/VIGNESH6579/RiskPilot-2026.git
cd RiskPilot-2026/riskpilot
```

### 2. Database Setup

#### For Development (H2 - In-memory)
No setup required - uses embedded H2 database.

#### For Production (MySQL)
```sql
CREATE DATABASE riskpilot CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'riskpilot'@'localhost' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON riskpilot.* TO 'riskpilot'@'localhost';
FLUSH PRIVILEGES;
```

### 3. Configuration

Copy and configure environment variables:
```bash
cp src/main/resources/application.yml.example src/main/resources/application.yml
```

#### Environment Variables
```bash
# Database Configuration
DATABASE_URL=jdbc:mysql://localhost:3306/riskpilot
DATABASE_USERNAME=riskpilot
DATABASE_PASSWORD=your_password

# Trading Configuration
TRADING_SYMBOL=BANKNIFTY
MAX_TRADES_PER_DAY=3
DEFAULT_STOP_LOSS=85
DEFAULT_TAKE_PROFIT=120

# Angel One API
ANGELONE_API_KEY=your_api_key
ANGELONE_CLIENT_CODE=your_client_code
ANGELONE_PASSWORD=your_password
ANGELONE_TOTP_SECRET=your_totp_secret

# Observer Configuration
OBSERVER_ENABLED=true
OBSERVER_CSV_PATH=shadow_live_forward_logs.csv
NTFY_ENABLED=true
NTFY_TOPIC=riskpilot_shadow_alerts
```

### 4. Build and Run

#### Using Maven
```bash
# Development
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Production
mvn clean package
java -jar target/riskpilot-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

#### Using Docker
```bash
# Build
docker build -t riskpilot-2026 .

# Run
docker run -p 8080:8080 -p 8765:8765 riskpilot-2026
```

#### Using Docker Compose
```bash
docker-compose up -d
```

## 📊 API Endpoints

### Trading Engine
- `GET /api/v1/trading/status` - Get trading engine status
- `GET /api/v1/trading/sessions/current` - Get current trading session
- `GET /api/v1/trading/trades/active` - Get active trades
- `GET /api/v1/trading/signals/recent` - Get recent signals
- `GET /api/v1/trading/trades/history` - Get trade history
- `POST /api/v1/trading/signals/manual` - Create manual signal
- `POST /api/v1/trading/trades/{tradeId}/close` - Close trade
- `GET /api/v1/trading/metrics/performance` - Get performance metrics

### Monitoring
- `GET /api/actuator/health` - Health check
- `GET /api/actuator/metrics` - Application metrics
- `GET /api/actuator/info` - Application info

### WebSocket
- `ws://localhost:8765` - Observer WebSocket connection

## 🔧 Configuration

### Trading Parameters
```yaml
riskpilot:
  trading:
    symbol: BANKNIFTY
    session-start: "09:15"
    session-end: "15:30"
    opening-range-end: "09:45"
    max-trades-per-day: 3
    max-position-size: 75
    default-stop-loss: 85
    default-take-profit: 120
    slippage-entry: 5.0
    slippage-exit: 5.0
    latency-threshold: 2.0
```

### Risk Management
```yaml
riskpilot:
  trading:
    early-kill-mae-limit: 25.0
    early-kill-fraction: 0.5
    tp1-offset: 18.0
    tp1-fraction: 0.2
```

## 📈 Monitoring

### Observer Dashboard
Access the observer dashboard at `http://localhost:8080/frontend.html`

### Health Checks
```bash
curl http://localhost:8080/api/actuator/health
```

### Metrics
```bash
curl http://localhost:8080/api/actuator/metrics
```

## 🧪 Testing

### Unit Tests
```bash
mvn test
```

### Integration Tests
```bash
mvn verify -P integration-test
```

### Backtesting
```bash
# Run backtest with default parameters
mvn exec:java -Dexec.mainClass="com.riskpilot.BacktestRunner"

# Custom backtest
mvn exec:java -Dexec.mainClass="com.riskpilot.BacktestRunner" \
  -Dexec.args="BANKNIFTY 5.0 5.0 TREND 18.0 0.2 999.0 1.0"
```

## 📝 Logging

### Log Levels
- `com.riskpilot`: Application logs
- `org.springframework.web`: Web framework logs
- `org.hibernate.SQL`: Database SQL logs

### Log Files
- Development: Console output
- Production: `logs/riskpilot-prod.log`

## 🚀 Deployment

### Render.com
1. Connect your GitHub repository to Render
2. Set environment variables in Render dashboard
3. Deploy using `render.yaml` configuration

### Docker Production
```bash
# Production build
docker build -t riskpilot:prod .

# Run with environment file
docker run -d \
  --name riskpilot-prod \
  -p 8080:8080 \
  -p 8765:8765 \
  --env-file .env.prod \
  riskpilot:prod
```

## 🔒 Security

### API Security
- Input validation on all endpoints
- Rate limiting implementation
- CORS configuration
- Secure credential management

### Data Security
- Encrypted database connections
- Environment variable configuration
- No hardcoded secrets

## 🛠️ Development

### Code Structure
```
src/main/java/com/riskpilot/
├── config/          # Configuration classes
├── controller/      # REST controllers
├── dto/            # Data transfer objects
├── exception/      # Exception handling
├── model/          # JPA entities
├── repository/      # Data access layer
├── service/        # Business logic
└── util/           # Utility classes
```

### Adding New Features
1. Create entity in `model/` package
2. Create repository interface in `repository/`
3. Implement service in `service/`
4. Add controller endpoint in `controller/`
5. Write tests in `src/test/`

## 📊 Performance

### System Requirements
- **CPU**: 2+ cores recommended
- **Memory**: 4GB+ RAM
- **Storage**: 10GB+ for logs and data
- **Network**: Stable internet connection for market data

### Optimization
- Connection pooling for database
- Caching for frequently accessed data
- Asynchronous processing for market data
- Efficient tick processing algorithms

## 🐛 Troubleshooting

### Common Issues

#### Database Connection Issues
```bash
# Check database connectivity
mysql -h localhost -u riskpilot -p riskpilot

# Verify configuration
cat src/main/resources/application.yml
```

#### Market Data Connection
```bash
# Check Angel One API credentials
curl -H "Authorization: Bearer YOUR_TOKEN" \
  https://apiconnect.angelbroking.com/rest/secure/angelbroking/api/v1/feed/
```

#### Memory Issues
```bash
# Increase JVM heap size
java -Xmx2g -Xms1g -jar riskpilot-0.0.1-SNAPSHOT.jar
```

### Debug Mode
```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dlogging.level.com.riskpilot=DEBUG"
```

## 📚 Documentation

- [API Documentation](http://localhost:8080/swagger-ui.html) (when enabled)
- [Actuator Endpoints](http://localhost:8080/api/actuator)
- [Observer Dashboard](http://localhost:8080/frontend.html)

## 🤝 Contributing

1. Fork the repository
2. Create feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open Pull Request

### Code Style
- Follow Java conventions
- Use meaningful variable names
- Add comprehensive comments
- Write unit tests for new features

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🆘 Support

For issues and questions:
- Create an issue on GitHub
- Check the troubleshooting section
- Review the logs for error details

## 🔄 Version History

### v1.0.0 (Current)
- Initial release with shadow execution engine
- Angel One API integration
- Real-time monitoring dashboard
- Comprehensive risk management
- Production-ready deployment configuration

---

**RiskPilot-2026** - Advanced Algorithmic Trading System for Modern Markets
