package com.example.camy

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

class ServiceLifecycleOwner : LifecycleOwner {

    private val registry = LifecycleRegistry(this)

    override fun getLifecycle(): Lifecycle = registry

    fun handleOnCreate() {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun handleOnStart() {
        registry.currentState = Lifecycle.State.STARTED
    }

    fun handleOnDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
