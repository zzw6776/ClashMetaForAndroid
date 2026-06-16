#!/usr/bin/env python3
import os
import sys
import json
import socket
import subprocess
from concurrent.futures import ThreadPoolExecutor

# Colors for terminal output
def print_cyan(text):
    print(f"\033[36m{text}\033[0m" if os.name != 'nt' else text)

def print_green(text):
    print(f"\033[32m{text}\033[0m" if os.name != 'nt' else text)

def print_red(text):
    print(f"\033[31m{text}\033[0m" if os.name != 'nt' else text)

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    config_file = os.path.join(script_dir, ".adb_config")
    
    default_ip = "192.168.50.114"
    last_port = ""
    
    # 1. Load configuration
    if os.path.exists(config_file):
        try:
            with open(config_file, 'r', encoding='utf-8') as f:
                config = json.load(f)
                default_ip = config.get("IP", default_ip)
                last_port = config.get("Port", "")
        except Exception:
            pass
            
    # 2. Get target IP from argument or config
    ip = sys.argv[1] if len(sys.argv) > 1 else default_ip
    
    print_cyan(f">>> Preparing connection to device: {ip}")
    
    # 3. Try to reuse last successful port
    if last_port:
        print(f"Trying last successful port: {last_port} ...")
        target = f"{ip}:{last_port}"
        try:
            res = subprocess.run(["adb", "connect", target], capture_output=True, text=True)
            output = res.stdout + res.stderr
            if "connected to" in output or "already connected to" in output:
                print_green("Success (reused last port)!")
                # Save config
                with open(config_file, 'w', encoding='utf-8') as f:
                    json.dump({"IP": ip, "Port": str(last_port)}, f, indent=4)
                subprocess.run(["adb", "devices"])
                return
        except Exception as e:
            print(f"Error calling adb: {e}")
            
    # 4. If direct connection fails, scan ports (35000-50000)
    print("Direct connection failed. Scanning ADB ports (35000-50000)...")
    
    open_ports = []
    
    def check_port(port):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(0.4) # 400ms timeout
        try:
            s.connect((ip, port))
            open_ports.append(port)
        except Exception:
            pass
        finally:
            s.close()
            
    ports = list(range(35000, 50001))
    
    # 100 max workers to prevent syn flood drops
    with ThreadPoolExecutor(max_workers=100) as executor:
        executor.map(check_port, ports)
        
    # 5. Try to connect to scanned open ports
    connected = False
    for port in sorted(open_ports):
        print(f"Trying port: {port} ...")
        target = f"{ip}:{port}"
        try:
            res = subprocess.run(["adb", "connect", target], capture_output=True, text=True)
            output = res.stdout + res.stderr
            if "connected to" in output or "already connected to" in output:
                print_green(f"Success! Connected to port: {port}")
                with open(config_file, 'w', encoding='utf-8') as f:
                    json.dump({"IP": ip, "Port": str(port)}, f, indent=4)
                connected = True
                break
        except Exception:
            pass
            
    if not connected:
        print_red(f"Failed to connect. Please make sure wireless debugging is enabled and IP {ip} is correct.")
        sys.exit(1)
    else:
        subprocess.run(["adb", "devices"])
        sys.exit(0)

if __name__ == "__main__":
    main()
