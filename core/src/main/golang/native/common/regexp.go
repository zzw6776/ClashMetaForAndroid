package common

import (
	"sync"
	"time"

	"github.com/dlclark/regexp2"
)

const RegexpMatchTimeout = 100 * time.Millisecond

var configureRegexpTimeoutOnce sync.Once

func ConfigureRegexpTimeout() {
	configureRegexpTimeoutOnce.Do(func() {
		regexp2.DefaultMatchTimeout = RegexpMatchTimeout
	})
}
