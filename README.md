# RiskPilot-2026

A production-grade algorithmic trading system with real-time Angel One market data integration.

## Features

✅ **Real-time Market Data** - Angel One API integration for live OHLC data  
✅ **Signal Generation** - Algorithmic trap trading strategy  
✅ **WebSocket Dashboard** - Real-time trade updates  
✅ **Alert System** - ntfy.sh notifications for trade exits  
✅ **Health Monitoring** - UptimeRobot integration  
✅ **Production Ready** - Secure environment-based credentials  

## Tech Stack

- **Backend**: Spring Boot 4.0.5 (Java 17)
- **Real-time Communication**: WebSocket + aiohttp
- **Market Data**: Angel One Broker API
- **Monitoring**: Prometheus + Actuator
- **Alerts**: ntfy.sh
- **Deployment**: Docker on Render.com

## Quick Start

### Prerequisites
- Angel One trading account
- Render.com account
- GitHub account

### Local Development

```bash
# Clone repository
git clone https://github.com/VIGNESH6579/RiskPilot-2026.git
cd RiskPilot-2026/riskpilot

# Set environment variables
export ANGEL_API_KEY=your_api_key
export ANGEL_CLIENT_ID=your_client_id
export ANGEL_PIN=your_pin
export ANGEL_TOTP_SECRET=your_totp_secret

# Build with Maven
mvn clean package

# Run application
java -jar target/riskpilot-0.0.1-SNAPSHOT.jar
```

## Production Deployment
Follow: PRODUCTION_SETUP.md

## API Endpoints

### Health & Monitoring
- `GET /api/v1/monitor/state` - Health status
- `GET /api/v1/monitor/detailed` - Detailed system status
- `GET /actuator/health` - Spring Boot health
- `GET /actuator/metrics` - Application metrics
- `GET /actuator/prometheus` - Prometheus metrics

### WebSocket
- `WS /ws/signals` - Real-time trade signal stream

## Configuration
All sensitive data is stored in environment variables:

```bash
ANGEL_API_KEY           # Angel One API key
ANGEL_CLIENT_ID         # Client ID
ANGEL_PIN              # Account PIN
ANGEL_TOTP_SECRET      # 2FA TOTP secret
RISKPILOT_NTFY_TOPIC   # ntfy.sh topic for alerts
DATABASE_URL           # MySQL connection string
```

## File Structure
```
riskpilot/
├── src/
│   ├── main/
│   │   ├── java/com/riskpilot/
│   │   │   ├── controller/    # REST API controllers
│   │   │   ├── service/       # Business logic
│   │   │   ├── dto/           # Data transfer objects
│   │   │   └── config/        # Spring configuration
│   │   └── resources/
│   │       ├── application.yaml
│   │       └── application-prod.yaml
│   └── test/
├── observer.py          # WebSocket observer (Python)
├── frontend.html        # Dashboard UI
├── Dockerfile          # Container configuration
├── pom.xml            # Maven dependencies
└── render.yaml        # Render.com deployment config
```

## Monitoring

### UptimeRobot
- Monitor: `/api/v1/monitor/state`
- Interval: 5 minutes
- Alerts: Email + Slack

### ntfy.sh
- Topic: riskpilot-live-signals
- Alerts: Real-time trade exits

### Prometheus Metrics
- Endpoint: `/actuator/prometheus`
- Key metrics: Request latency, memory usage, uptime

## Troubleshooting

### Angel One Auth Fails
- Verify credentials in environment variables
- Check TOTP secret is valid
- Ensure market hours (9:15 AM - 3:30 PM IST)

### WebSocket Not Connecting
- Check Observer running on port 8765
- Verify firewall allows WebSocket
- Check browser console for errors

### No Trade Signals
- Verify Angel One authentication
- Check market is open
- Verify CSV file is writable

## Contributing

1. Create a feature branch: `git checkout -b feature/your-feature`
2. Commit changes: `git commit -m 'Add feature'`
3. Push to branch: `git push origin feature/your-feature`
4. Open a pull request

## License
Private - RiskPilot-2026

## Support
For issues and questions, please open a GitHub issue.

## Roadmap
- Machine learning signal optimization
- Multi-leg strategy support
- Advanced risk management
- Mobile app integration
- Redis message queue (replace CSV)
