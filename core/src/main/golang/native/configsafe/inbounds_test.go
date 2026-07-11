package configsafe

import (
	"encoding/json"
	"net/netip"
	"reflect"
	"testing"

	"github.com/metacubex/mihomo/config"
	LC "github.com/metacubex/mihomo/listener/config"
)

func untrustedInboundConfig() *config.RawConfig {
	cfg := config.DefaultRawConfig()
	cfg.Port = 7890
	cfg.SocksPort = 7891
	cfg.RedirPort = 7892
	cfg.TProxyPort = 7893
	cfg.MixedPort = 7894
	cfg.ShadowSocksConfig = "enabled"
	cfg.VmessConfig = "enabled"
	cfg.Authentication = []string{"user:password"}
	cfg.SkipAuthPrefixes = []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0")}
	cfg.LanAllowedIPs = []netip.Prefix{netip.MustParsePrefix("192.168.0.0/16")}
	cfg.LanDisAllowedIPs = []netip.Prefix{netip.MustParsePrefix("192.168.1.0/24")}
	cfg.AllowLan = true
	cfg.BindAddress = "0.0.0.0"
	cfg.Listeners = []map[string]any{{
		"name":   "remote-mixed",
		"type":   "mixed",
		"listen": "0.0.0.0",
		"port":   1080,
	}}
	cfg.Tunnels = []LC.Tunnel{{
		Network: []string{"tcp"},
		Address: "0.0.0.0:8080",
		Target:  "example.com:80",
	}}
	cfg.TuicServer.Enable = true
	cfg.TuicServer.Listen = "0.0.0.0:443"
	cfg.IPTables.Enable = true
	cfg.DNS.Listen = "0.0.0.0:53"
	cfg.DNS.ListenRoutingMark = 123
	cfg.ExternalController = "0.0.0.0:9090"
	cfg.ExternalControllerTLS = "0.0.0.0:9443"
	cfg.ExternalControllerPipe = "remote-pipe"
	cfg.ExternalControllerUnix = "remote.sock"
	cfg.ExternalControllerRoutingMark = 456
	cfg.ExternalControllerCors.AllowOrigins = []string{"*"}
	cfg.ExternalDohServer = "0.0.0.0:8053"
	cfg.Secret = "remote-secret"

	return cfg
}

func TestPreservesFileProfileInbounds(t *testing.T) {
	cfg := untrustedInboundConfig()
	want := *cfg

	ApplyInboundPolicy(cfg, true)

	if !reflect.DeepEqual(*cfg, want) {
		t.Fatal("file profile inbound configuration was unexpectedly changed")
	}
}

func TestSanitizesRemoteProfileInbounds(t *testing.T) {
	cfg := untrustedInboundConfig()
	defaults := config.DefaultRawConfig()

	ApplyInboundPolicy(cfg, false)

	if cfg.Port != 0 || cfg.SocksPort != 0 || cfg.RedirPort != 0 || cfg.TProxyPort != 0 || cfg.MixedPort != 0 {
		t.Fatal("standard proxy ports were not disabled")
	}
	if cfg.ShadowSocksConfig != "" || cfg.VmessConfig != "" {
		t.Fatal("built-in proxy servers were not disabled")
	}
	if len(cfg.Listeners) != 0 || len(cfg.Tunnels) != 0 {
		t.Fatal("custom listeners or tunnels were not removed")
	}
	if cfg.TuicServer.Enable || cfg.IPTables.Enable {
		t.Fatal("TUIC server or iptables was not disabled")
	}
	if cfg.DNS.Listen != "" || cfg.DNS.ListenRoutingMark != 0 {
		t.Fatal("DNS listener was not disabled")
	}
	if cfg.AllowLan != defaults.AllowLan || cfg.BindAddress != defaults.BindAddress {
		t.Fatal("LAN listener policy was not reset")
	}
	if !reflect.DeepEqual(cfg.Authentication, defaults.Authentication) ||
		!reflect.DeepEqual(cfg.SkipAuthPrefixes, defaults.SkipAuthPrefixes) ||
		!reflect.DeepEqual(cfg.LanAllowedIPs, defaults.LanAllowedIPs) ||
		!reflect.DeepEqual(cfg.LanDisAllowedIPs, defaults.LanDisAllowedIPs) {
		t.Fatal("listener authentication or LAN ranges were not reset")
	}
	if cfg.ExternalController != "" || cfg.ExternalControllerTLS != "" ||
		cfg.ExternalControllerPipe != "" || cfg.ExternalControllerUnix != "" ||
		cfg.ExternalControllerRoutingMark != 0 || cfg.ExternalDohServer != "" || cfg.Secret != "" {
		t.Fatal("controller listeners were not disabled")
	}
	if !reflect.DeepEqual(cfg.ExternalControllerCors, defaults.ExternalControllerCors) {
		t.Fatal("controller CORS policy was not reset")
	}
}

func TestApplicationOverrideRestoresSupportedListeners(t *testing.T) {
	cfg := untrustedInboundConfig()
	ApplyInboundPolicy(cfg, false)

	if err := json.Unmarshal([]byte(`{
		"port": 7890,
		"socks-port": 7891,
		"mixed-port": 7897,
		"allow-lan": false,
		"bind-address": "127.0.0.1",
		"authentication": ["local:password"],
		"dns": {"listen": "127.0.0.1:5353"}
	}`), cfg); err != nil {
		t.Fatalf("apply application override: %v", err)
	}

	if cfg.Port != 7890 || cfg.SocksPort != 7891 || cfg.MixedPort != 7897 {
		t.Fatal("supported proxy ports were not restored by the application override")
	}
	if cfg.AllowLan || cfg.BindAddress != "127.0.0.1" {
		t.Fatal("application LAN policy override was not applied")
	}
	if !reflect.DeepEqual(cfg.Authentication, []string{"local:password"}) {
		t.Fatal("application authentication override was not applied")
	}
	if cfg.DNS.Listen != "127.0.0.1:5353" {
		t.Fatal("application DNS listener override was not applied")
	}
	if len(cfg.Listeners) != 0 || len(cfg.Tunnels) != 0 {
		t.Fatal("remote custom listeners or tunnels unexpectedly survived")
	}
}
