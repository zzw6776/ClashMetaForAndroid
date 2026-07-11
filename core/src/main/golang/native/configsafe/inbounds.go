package configsafe

import "github.com/metacubex/mihomo/config"

// ApplyInboundPolicy removes every inbound service controlled by an untrusted
// profile. Trusted application overrides are applied by the caller afterwards.
func ApplyInboundPolicy(cfg *config.RawConfig, allowConfigInbounds bool) {
	if allowConfigInbounds {
		return
	}

	defaults := config.DefaultRawConfig()

	cfg.Port = 0
	cfg.SocksPort = 0
	cfg.RedirPort = 0
	cfg.TProxyPort = 0
	cfg.MixedPort = 0
	cfg.ShadowSocksConfig = ""
	cfg.VmessConfig = ""
	cfg.Authentication = defaults.Authentication
	cfg.SkipAuthPrefixes = defaults.SkipAuthPrefixes
	cfg.LanAllowedIPs = defaults.LanAllowedIPs
	cfg.LanDisAllowedIPs = defaults.LanDisAllowedIPs
	cfg.AllowLan = defaults.AllowLan
	cfg.BindAddress = defaults.BindAddress

	cfg.Listeners = nil
	cfg.Tunnels = nil
	cfg.TuicServer = defaults.TuicServer
	cfg.IPTables = defaults.IPTables

	cfg.DNS.Listen = defaults.DNS.Listen
	cfg.DNS.ListenRoutingMark = defaults.DNS.ListenRoutingMark

	cfg.ExternalController = ""
	cfg.ExternalControllerTLS = ""
	cfg.ExternalControllerPipe = ""
	cfg.ExternalControllerUnix = ""
	cfg.ExternalControllerRoutingMark = 0
	cfg.ExternalControllerCors = defaults.ExternalControllerCors
	cfg.ExternalDohServer = ""
	cfg.Secret = ""
}
