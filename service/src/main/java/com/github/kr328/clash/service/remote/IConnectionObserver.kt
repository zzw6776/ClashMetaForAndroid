package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.model.ConnectionDiff
import com.github.kr328.kaidl.BinderInterface

@BinderInterface
interface IConnectionObserver {
    fun onConnectionDiff(diff: ConnectionDiff)
}
