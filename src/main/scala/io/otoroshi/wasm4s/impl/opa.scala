package io.otoroshi.wasm4s.impl

import io.otoroshi.wasm4s.scaladsl._
import implicits._
import org.extism.sdk.{ExtismCurrentPlugin, ExtismFunction, HostFunction, LibExtism, Plugin}
import org.extism.sdk.wasmotoroshi._
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import java.util.Optional;

object OPA extends AwaitCapable {

  def opaAbortFunction: ExtismFunction[EmptyUserData] =
    (
      plugin: ExtismCurrentPlugin,
      params: Array[LibExtism.ExtismVal],
      returns: Array[LibExtism.ExtismVal],
      data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaAbortFunction");
    }

  def opaPrintlnFunction: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaPrintlnFunction");
    }

  def opaBuiltin0Function: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin0Function");
    }

  def opaBuiltin1Function: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin1Function");
    }

  def opaBuiltin2Function: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin2Function");
    }

  def opaBuiltin3Function: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin3Function");
    };

  def opaBuiltin4Function: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("opaBuiltin4Function");
    }

  def opaAbort() = new HostFunction[EmptyUserData](
    "opa_abort",
    Array(LibExtism.ExtismValType.I32),
    Array(),
    opaAbortFunction,
    Optional.empty()
  ).withNamespace("env")

  def opaPrintln() = new HostFunction[EmptyUserData](
    "opa_println",
    Array(LibExtism.ExtismValType.I64),
    Array(LibExtism.ExtismValType.I64),
    opaPrintlnFunction,
    Optional.empty()
  ).withNamespace("env")

  def opaBuiltin0() = new HostFunction[EmptyUserData](
    "opa_builtin0",
    Array(LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    opaBuiltin0Function,
    Optional.empty()
  ).withNamespace("env")

  def opaBuiltin1() = new HostFunction[EmptyUserData](
    "opa_builtin1",
    Array(LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    opaBuiltin1Function,
    Optional.empty()
  ).withNamespace("env")

  def opaBuiltin2() = new HostFunction[EmptyUserData](
    "opa_builtin2",
    Array(
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32
    ),
    Array(LibExtism.ExtismValType.I32),
    opaBuiltin2Function,
    Optional.empty()
  ).withNamespace("env")

  def opaBuiltin3() = new HostFunction[EmptyUserData](
    "opa_builtin3",
    Array(
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32
    ),
    Array(LibExtism.ExtismValType.I32),
    opaBuiltin3Function,
    Optional.empty()
  ).withNamespace("env")

  def opaBuiltin4() = new HostFunction[EmptyUserData](
    "opa_builtin4",
    Array(
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32,
      LibExtism.ExtismValType.I32
    ),
    Array(LibExtism.ExtismValType.I32),
    opaBuiltin4Function,
    Optional.empty()
  ).withNamespace("env")

  def getFunctions(config: WasmConfiguration): Seq[HostFunctionWithAuthorization] = {
    Seq(
      HostFunctionWithAuthorization(opaAbort(), _ => config.opa),
      HostFunctionWithAuthorization(opaPrintln(), _ => config.opa),
      HostFunctionWithAuthorization(opaBuiltin0(), _ => config.opa),
      HostFunctionWithAuthorization(opaBuiltin1(), _ => config.opa),
      HostFunctionWithAuthorization(opaBuiltin2(), _ => config.opa),
      HostFunctionWithAuthorization(opaBuiltin3(), _ => config.opa),
      HostFunctionWithAuthorization(opaBuiltin4(), _ => config.opa)
    )
  }

  def getLinearMemories(): Seq[LinearMemory] = {
    Seq(
      new LinearMemory("memory", "env", new LinearMemoryOptions(4, Optional.empty()))
    )
  }

  def loadJSON(plugin: Plugin, value: Array[Byte]): Either[JsValue, Int] = {
    if (value.length == 0) {
      0.right
    } else {
      val value_buf_len = value.length
      var parameters    = new Parameters(1)
        .pushInt(value_buf_len)

      val raw_addr = plugin.call("opa_malloc", parameters, 1)

      if (
        plugin.writeBytes(
          value,
          value_buf_len,
          raw_addr.getValue(0).v.i32,
          "env",
          "memory"
        ) == -1
      ) {
        JsString("Cant' write in memory").left
      } else {
        parameters = new Parameters(2)
          .pushInts(raw_addr.getValue(0).v.i32, value_buf_len)
        val parsed_addr = plugin.call(
          "opa_json_parse",
          parameters,
          1
        )

        if (parsed_addr.getValue(0).v.i32 == 0) {
          JsString("failed to parse json value").left
        } else {
          parsed_addr.getValue(0).v.i32.right
        }
      }
    }
  }

  def initialize(plugin: Plugin): Either[JsValue, (String, ResultsWrapper)] = {
    loadJSON(plugin, "{}".getBytes(StandardCharsets.UTF_8))
      .flatMap(dataAddr => {
        val base_heap_ptr = plugin.call(
          "opa_heap_ptr_get",
          new Parameters(0),
          1
        )

        val data_heap_ptr = base_heap_ptr.getValue(0).v.i32
        (
          Json.obj("dataAddr" -> dataAddr, "baseHeapPtr" -> data_heap_ptr).stringify,
          ResultsWrapper(new Results(0))
        ).right
      })
  }

  def evaluate(
      plugin: Plugin,
      dataAddr: Int,
      baseHeapPtr: Int,
      input: String
  ): Either[JsValue, (String, ResultsWrapper)] = {
    val entrypoint = 0

    // TODO - read and load builtins functions by calling dumpJSON
    val input_len = input.getBytes(StandardCharsets.UTF_8).length
    plugin.writeBytes(
      input.getBytes(StandardCharsets.UTF_8),
      input_len,
      baseHeapPtr,
      "env",
      "memory"
    )

    val heap_ptr   = baseHeapPtr + input_len
    val input_addr = baseHeapPtr

    val ptr = new Parameters(7)
      .pushInts(0, entrypoint, dataAddr, input_addr, input_len, heap_ptr, 0)

    val ret = plugin.call("opa_eval", ptr, 1)

    val memory = plugin.getLinearMemory( "env", "memory")

    val offset: Int    = ret.getValue(0).v.i32
    val arraySize: Int = 65356

    val mem: Array[Byte] = memory.getByteArray(offset, arraySize)
    val size: Int        = lastValidByte(mem)

    (
      new String(java.util.Arrays.copyOf(mem, size), StandardCharsets.UTF_8),
      ResultsWrapper(new Results(0))
    ).right
  }

  def lastValidByte(arr: Array[Byte]): Int = {
    for (i <- arr.indices) {
      if (arr(i) == 0) {
        return i
      }
    }
    arr.length
  }
}


/*
    String dumpJSON() {
        Results addr = plugin.call("builtins",  new Parameters(0), 1);

        Parameters parameters = new Parameters(1);
        IntegerParameter builder = new IntegerParameter();
        builder.add(parameters, addr.getValue(0).v.i32, 0);

        Results rawAddr = plugin.call("opa_json_dump", parameters, 1);

        Pointer memory = LibExtism.INSTANCE.extism_get_memory(plugin.getPointer(), plugin.getIndex(), "memory");
        byte[] mem = memory.getByteArray(rawAddr.getValue(0).v.i32, 65356);
        int size = lastValidByte(mem);

        return new String(Arrays.copyOf(mem, size), StandardCharsets.UTF_8);
    }
}*/
