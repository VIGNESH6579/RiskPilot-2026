import yfinance as yf
import pandas as pd

try:
    print("Fetching NIFTY 50 data...")
    # Yahoo symbol for NIFTY 50 is ^NSEI
    nifty = yf.Ticker("^NSEI")
    
    # Fetch 60 days (max allowed for 5m interval in yahoo)
    data = nifty.history(period="60d", interval="5m")
    
    # Format according to java requirements
    data.reset_index(inplace=True)
    
    # The datetime column might be named 'Datetime'
    data['time'] = data['Datetime'].dt.strftime('%Y-%m-%d %H:%M:%S')
    
    # Select columns
    df = data[['time', 'Open', 'High', 'Low', 'Close']]
    
    df.to_csv("nifty_5m.csv", index=False)
    print("Successfully downloaded and saved 'nifty_5m.csv'")
except Exception as e:
    print(f"Error: {e}")
