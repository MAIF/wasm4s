package io.otoroshi.wasm4s.scaladsl.opa

import io.otoroshi.wasm4s.scaladsl.WasmConfiguration
import org.extism.sdk.wasmotoroshi.LinearMemory

import java.util.concurrent.atomic.AtomicReference

object LinearMemories {

  private val memories: AtomicReference[Seq[LinearMemory]] =
    new AtomicReference[Seq[LinearMemory]](Seq.empty[LinearMemory])

  def getMemories(config: WasmConfiguration): Array[LinearMemory] = {
    if (config.opa) {
      if (memories.get.isEmpty) {
        memories.set(
          io.otoroshi.wasm4s.impl.OPA.getLinearMemories(config.memoryPages)
        )
      }
      memories.get().toArray
    } else {
      Array.empty
    }
  }
}
