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

def run_adb(args, timeout=10):
    try:
        res = subprocess.run(
            ["adb", *args],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        return (res.stdout or "") + (res.stderr or "")
    except subprocess.TimeoutExpired:
        return "adb command timed out"
    except Exception as e:
        return f"Error calling adb: {e}"

def try_adb_connect(ip, port, config_file):
    target = f"{ip}:{port}"
    output = run_adb(["connect", target], timeout=8)
    if "connected to" in output or "already connected to" in output:
        print_green(f"Success! Connected to port: {port}")
        with open(config_file, 'w', encoding='utf-8') as f:
            json.dump({"IP": ip, "Port": str(port)}, f, indent=4)
        subprocess.run(["adb", "devices"])
        return True
    return False

def discover_mdns_ports(ip):
    output = run_adb(["mdns", "services"], timeout=5)
    ports = []
    for line in output.splitlines():
        if "_adb-tls-connect._tcp" not in line:
            continue
        marker = f"{ip}:"
        if marker not in line:
            continue
        port_text = line.rsplit(marker, 1)[-1].strip().split()[0]
        if port_text.isdigit():
            ports.append(int(port_text))
    return sorted(set(ports))

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
        if try_adb_connect(ip, last_port, config_file):
            print_green("Success (reused last port)!")
            return
            
    # 4. Prefer mDNS discovery; it is much faster than scanning.
    print("Direct connection failed. Discovering ADB ports via mDNS...")
    for port in discover_mdns_ports(ip):
        print(f"Trying mDNS port: {port} ...")
        if try_adb_connect(ip, port, config_file):
            return

    # 5. If mDNS fails, scan ports (35000-50000)
    print("Direct connection failed. Scanning ADB ports (35000-50000)...")
    
    open_ports = []
    
    def check_port(port):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(0.15)
        try:
            s.connect((ip, port))
            open_ports.append(port)
        except Exception:
            pass
        finally:
            s.close()
            
    ports = list(range(35000, 50001))
    
    with ThreadPoolExecutor(max_workers=300) as executor:
        executor.map(check_port, ports)
        
    # 6. Try to connect to scanned open ports
    connected = False
    for port in sorted(open_ports):
        print(f"Trying port: {port} ...")
        if try_adb_connect(ip, port, config_file):
            connected = True
            break
            
    if not connected:
        print_red(f"Failed to connect. Please make sure wireless debugging is enabled and IP {ip} is correct.")
        sys.exit(1)
    else:
        subprocess.run(["adb", "devices"])
        sys.exit(0)

if __name__ == "__main__":
    main()
