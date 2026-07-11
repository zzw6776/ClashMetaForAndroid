package app

import (
	"sync"
	"testing"
)

func TestInstalledAppsConcurrentAccess(t *testing.T) {
	var wait sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		wait.Add(1)
		go func(worker int) {
			defer wait.Done()
			for iteration := 0; iteration < 500; iteration++ {
				if worker%2 == 0 {
					NotifyInstallAppsChanged("1000:system,1001:example")
				} else {
					_ = QueryAppByUid(1000 + iteration%2)
				}
			}
		}(worker)
	}
	wait.Wait()
}

func TestSubtitlePatternConcurrentAccess(t *testing.T) {
	var wait sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		wait.Add(1)
		go func(worker int) {
			defer wait.Done()
			for iteration := 0; iteration < 100; iteration++ {
				if worker%2 == 0 {
					ApplySubtitlePattern(`\[[^]]+\]`)
					continue
				}
				pattern := SubtitlePattern()
				if pattern != nil {
					_, _ = pattern.FindStringMatch("proxy [provider]")
				}
			}
		}(worker)
	}
	wait.Wait()
}

func TestInvalidSubtitlePatternIsCleared(t *testing.T) {
	ApplySubtitlePattern("[")
	if pattern := SubtitlePattern(); pattern != nil {
		t.Fatalf("invalid pattern was retained: %s", pattern.String())
	}
}
