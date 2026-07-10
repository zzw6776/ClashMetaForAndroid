package tunnel

import (
	"github.com/metacubex/mihomo/tunnel/statistic"
)

func ResetStatistic() {
	statistic.DefaultManager.ResetStatistic()
}

func Now() (up int64, down int64) {
	return statistic.DefaultManager.Now()
}

func Total() (up int64, down int64) {
	return statistic.DefaultManager.Total()
}

func QueryConnections() *statistic.Snapshot {
	return statistic.DefaultManager.HistorySnapshot()
}

func PeekConnectionHistoryEvents(limit int) *statistic.HistoryEvents {
	return statistic.DefaultManager.PeekHistoryEvents(limit)
}

func AckConnectionHistoryEvents(token, sequence uint64) {
	statistic.DefaultManager.AckHistoryEvents(token, sequence)
}

func SetConnectionHistoryEnabled(enabled bool, session string) {
	statistic.DefaultManager.SetHistoryEnabled(enabled, session)
}

func ConnectionHistoryEnabled() bool {
	return statistic.DefaultManager.HistoryEnabled()
}

func CloseConnection(id string) {
	if c := statistic.DefaultManager.Get(id); c != nil {
		_ = c.Close()
	}
}
