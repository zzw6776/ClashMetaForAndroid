package com.github.kr328.clash.service

import kotlinx.coroutines.sync.Mutex

/** Serializes database and file-system mutations for profile directories. */
internal val profileFileLock = Mutex()
