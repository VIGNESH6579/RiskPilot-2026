import yfinance as yf
import pandas as pd

print("Fetching BankNifty 5m data (Max ~60 days via YFinance)..")
# ^NSEBANK is Nifty Bank Index on Yahoo Finance
data = yf.download("^NSEBANK", period="60d", interval="5m")

if data.empty:
    print("Error: No data retrieved.")
    exit()

data.reset_index(inplace=True)

# yf usually returns column 'Datetime'
if 'Datetime' in data.columns:
    data.rename(columns={'Datetime': 'time'}, inplace=True)
elif 'Date' in data.columns:
    data.rename(columns={'Date': 'time'}, inplace=True)

# Format time to "YYYY-MM-DD HH:MM:00" mapping exactly
# Strip timezones if present to avoid java parsing crash
if hasattr(data['time'].dt, 'tz_convert') and data['time'].dt.tz is not None:
    data['time'] = data['time'].dt.tz_convert('Asia/Kolkata').dt.tz_localize(None)

data['time'] = data['time'].dt.strftime('%Y-%m-%d %H:%M:%00')

# Extract required columns
df = data[['time', 'Open', 'High', 'Low', 'Close']]

df.to_csv('banknifty_5m.csv', index=False)
print("Data saved successfully to banknifty_5m.csv:", len(df), "records.")
