package app

import (
	"sync"

	"github.com/dlclark/regexp2"

	"github.com/metacubex/mihomo/log"
)

var uiSubtitlePatternMutex sync.RWMutex
var uiSubtitlePattern string

func ApplySubtitlePattern(pattern string) {
	if pattern != "" {
		if _, err := regexp2.Compile(pattern, regexp2.IgnoreCase|regexp2.Compiled); err != nil {
			pattern = ""
			log.Warnln("Compile ui-subtitle-pattern: %s", err.Error())
		}
	}

	uiSubtitlePatternMutex.Lock()
	uiSubtitlePattern = pattern
	uiSubtitlePatternMutex.Unlock()
}

func SubtitlePattern() *regexp2.Regexp {
	uiSubtitlePatternMutex.RLock()
	pattern := uiSubtitlePattern
	uiSubtitlePatternMutex.RUnlock()
	if pattern == "" {
		return nil
	}

	reg, err := regexp2.Compile(pattern, regexp2.IgnoreCase|regexp2.Compiled)
	if err != nil {
		log.Warnln("Compile ui-subtitle-pattern: %s", err.Error())
		return nil
	}
	return reg
}
