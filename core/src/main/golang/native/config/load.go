package config

import (
	"os"
	P "path"
	"runtime"
	"strings"

	"cfa/native/app"

	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/log"
)

func logDns(cfg *config.RawConfig) {
	bytes, err := yaml.Marshal(&cfg.DNS)
	if err != nil {
		log.Warnln("Marshal dns: %s", err.Error())

		return
	}

	log.Infoln("dns:")

	for _, line := range strings.Split(string(bytes), "\n") {
		log.Infoln("  %s", line)
	}
}

func UnmarshalAndPatch(profilePath string, allowConfigInbounds bool) (*config.RawConfig, error) {
	return unmarshalAndPatch(profilePath, allowConfigInbounds, config.UnmarshalRawConfig)
}

func UnmarshalAndPatchWithSecretKeys(
	profilePath string,
	allowConfigInbounds bool,
	secretKeys ...string,
) (*config.RawConfig, error) {
	return unmarshalAndPatch(profilePath, allowConfigInbounds, func(data []byte) (*config.RawConfig, error) {
		return config.UnmarshalRawConfigWithSecretKeys(data, secretKeys...)
	})
}

func unmarshalAndPatch(
	profilePath string,
	allowConfigInbounds bool,
	unmarshal func([]byte) (*config.RawConfig, error),
) (*config.RawConfig, error) {
	configPath := P.Join(profilePath, "config.yaml")

	configData, err := os.ReadFile(configPath)
	if err != nil {
		return nil, err
	}

	rawConfig, err := unmarshal(configData)
	if err != nil {
		return nil, err
	}

	if err := process(rawConfig, profilePath, allowConfigInbounds); err != nil {
		return nil, err
	}

	return rawConfig, nil
}

func Parse(rawConfig *config.RawConfig) (*config.Config, error) {
	cfg, err := config.ParseRawConfig(rawConfig)
	if err != nil {
		return nil, err
	}

	return cfg, nil
}

func Load(path string, allowConfigInbounds bool) error {
	rawCfg, err := UnmarshalAndPatch(path, allowConfigInbounds)
	if err != nil {
		log.Errorln("Load %s: %s", path, err.Error())

		return err
	}

	logDns(rawCfg)

	cfg, err := Parse(rawCfg)
	if err != nil {
		log.Errorln("Load %s: %s", path, err.Error())

		return err
	}

	// like hub.Parse()
	hub.ApplyConfig(cfg)

	app.ApplySubtitlePattern(rawCfg.ClashForAndroid.UiSubtitlePattern)

	runtime.GC()

	return nil
}

func LoadDefault() {
	cfg, err := config.Parse([]byte{})
	if err != nil {
		panic(err.Error())
	}

	hub.ApplyConfig(cfg)
}
