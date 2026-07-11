package common

import (
	"strings"
	"testing"

	"github.com/dlclark/regexp2"
)

func TestConfigureRegexpTimeout(t *testing.T) {
	ConfigureRegexpTimeout()

	pattern, err := regexp2.Compile(`^(a+)+$`, regexp2.None)
	if err != nil {
		t.Fatalf("compile test pattern: %v", err)
	}
	if pattern.MatchTimeout != RegexpMatchTimeout {
		t.Fatalf("unexpected match timeout: got %s, want %s", pattern.MatchTimeout, RegexpMatchTimeout)
	}
}

func TestRegexpMatchTimesOut(t *testing.T) {
	ConfigureRegexpTimeout()

	pattern, err := regexp2.Compile(`^(a+)+$`, regexp2.None)
	if err != nil {
		t.Fatalf("compile test pattern: %v", err)
	}
	if _, err = pattern.MatchString(strings.Repeat("a", 64) + "!"); err == nil {
		t.Fatal("catastrophic backtracking pattern did not time out")
	}
}
