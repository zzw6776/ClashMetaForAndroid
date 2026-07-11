package main

//#include "bridge.h"
import "C"

import (
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/metacubex/mihomo/log"
)

type message struct {
	Level   string `json:"level"`
	Message string `json:"message"`
	Time    int64  `json:"time"`
}

var logcatSubscriptionID atomic.Uint64
var logcatSubscriptionsMutex sync.Mutex
var logcatSubscriptions = map[uint64]chan struct{}{}

func init() {
	go func() {
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

		for msg := range sub {
			cPayload := C.CString(msg.Payload)

			switch msg.LogLevel {
			case log.INFO:
				C.log_info(cPayload)
			case log.ERROR:
				C.log_error(cPayload)
			case log.WARNING:
				C.log_warn(cPayload)
			case log.DEBUG:
				C.log_debug(cPayload)
			case log.SILENT:
				C.log_verbose(cPayload)
			}
		}
	}()
}

//export subscribeLogcat
func subscribeLogcat(remote unsafe.Pointer) C.longlong {
	id := logcatSubscriptionID.Add(1)
	if id == 0 {
		id = logcatSubscriptionID.Add(1)
	}
	closed := make(chan struct{})
	logcatSubscriptionsMutex.Lock()
	logcatSubscriptions[id] = closed
	logcatSubscriptionsMutex.Unlock()

	go func(remote unsafe.Pointer, id uint64, closed <-chan struct{}) {
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)
		defer C.release_object(remote)
		defer func() {
			logcatSubscriptionsMutex.Lock()
			delete(logcatSubscriptions, id)
			logcatSubscriptionsMutex.Unlock()
		}()

		for {
			var msg log.Event
			select {
			case <-closed:
				return
			case next, ok := <-sub:
				if !ok {
					return
				}
				msg = next
			}
			if msg.LogLevel < log.Level() && !strings.HasPrefix(msg.Payload, "[APP]") {
				continue
			}

			rMsg := &message{
				Level:   msg.LogLevel.String(),
				Message: msg.Payload,
				Time:    time.Now().UnixNano() / 1000 / 1000,
			}

			if C.logcat_received(remote, marshalJson(rMsg)) != 0 {
				log.Debugln("Logcat subscriber closed")

				break
			}
		}
	}(remote, id, closed)

	log.Infoln("[APP] Logcat level: %s", log.Level().String())
	return C.longlong(id)
}

//export unsubscribeLogcat
func unsubscribeLogcat(subscription C.longlong) {
	id := uint64(subscription)
	logcatSubscriptionsMutex.Lock()
	closed, exists := logcatSubscriptions[id]
	if exists {
		delete(logcatSubscriptions, id)
		close(closed)
	}
	logcatSubscriptionsMutex.Unlock()
}
