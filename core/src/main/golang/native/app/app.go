package app

import (
	"strconv"
	"strings"
	"sync"
	"time"
)

var appVersionName string
var platformVersion int
var installedAppsMutex sync.RWMutex
var installedAppsUid = map[int]string{}

func ApplyVersionName(versionName string) {
	appVersionName = versionName
}

func ApplyPlatformVersion(version int) {
	platformVersion = version
}

func VersionName() string {
	return appVersionName
}

func PlatformVersion() int {
	return platformVersion
}

func NotifyInstallAppsChanged(uidList string) {
	uids := map[int]string{}

	for _, item := range strings.Split(uidList, ",") {
		kv := strings.Split(item, ":")
		if len(kv) == 2 {
			uid, err := strconv.Atoi(kv[0])
			if err != nil {
				continue
			}

			uids[uid] = kv[1]
		}
	}

	installedAppsMutex.Lock()
	installedAppsUid = uids
	installedAppsMutex.Unlock()
}

func QueryAppByUid(uid int) string {
	installedAppsMutex.RLock()
	name := installedAppsUid[uid]
	installedAppsMutex.RUnlock()
	return name
}

func NotifyTimeZoneChanged(name string, offset int) {
	time.Local = time.FixedZone(name, offset)
}
