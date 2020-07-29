package sodor.common

import chisel3._
import chisel3.util._
import chisel3.experimental._

import freechips.rocketchip.rocket._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.diplomaticobjectmodel.model.OMSRAM
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._

class SodorScratchpadAdapter(implicit p: Parameters, implicit val sodorConf: SodorConfiguration) extends Module {
  // Parameter traits
  val coreParams = {
    class C(implicit val p: Parameters) extends HasCoreParameters
    new C
  }
  // Extract tileParams from HasNonDiplomaticTileParameters, which is the base trait of HasCoreParameters above
  val tileParams = coreParams.tileParams
  // Sodor config class

  // Sodor constants
  val sodorConst = {
    class S extends MemoryOpConstants
    new S
  }

  val io = IO(new Bundle() {
    val slavePort = Flipped(new HellaCacheIO())
    val memPort = new MemPortIo(data_width = coreParams.coreDataBits)
  })

  // ===================
  // Slave port signals
  val slave_req_ready = io.slavePort.req.ready
  val slave_resp_valid = io.slavePort.resp.valid
  val slave_req_valid = io.slavePort.req.valid

  val slave_cmd = io.slavePort.req.bits.cmd
  val slave_req_addr = io.slavePort.req.bits.addr(log2Ceil(tileParams.dcache.get.dataScratchpadBytes), 0)
  val slave_req_size = io.slavePort.req.bits.size
  val slave_req_signed = io.slavePort.req.bits.signed

  // All request are delayed for one cycle to avoid being killed
  val s1_slave_write_kill = io.slavePort.s1_kill
  val s1_slave_write_data = io.slavePort.s1_data.data
  val s1_slave_write_mask = io.slavePort.s1_data.mask

  val s1_slave_req_valid = RegNext(slave_req_valid)
  val s1_slave_cmd = RegNext(slave_cmd)
  val s1_slave_req_addr = RegNext(slave_req_addr)
  val s1_slave_req_size = RegNext(slave_req_size)
  val s1_slave_req_signed = RegNext(slave_req_signed)
  val s1_slave_read_data = io.slavePort.resp.bits.data_raw
  val s1_slave_read_mask = io.slavePort.resp.bits.mask

  val s2_nack = io.slavePort.s2_nack

  // Tie anything not defined below to DontCare
  io.slavePort := DontCare

  // ===================
  // HellaCacheIO to MemPortIo logic
  // Connect valid & ready bits
  slave_req_ready := io.memPort.req.ready
  io.memPort.req.valid := s1_slave_req_valid & s1_slave_cmd === M_XRD & !s1_slave_write_kill
  slave_resp_valid := io.memPort.resp.valid & s1_slave_cmd === M_XRD

  // Connect read info
  s1_slave_read_mask := new StoreGen(s1_slave_req_size, s1_slave_req_addr, 0.U, coreParams.coreDataBytes).mask
  s1_slave_read_data := io.memPort.resp.bits.data

  // Connect write info
  io.memPort.req.bits.addr := s1_slave_req_addr
  io.memPort.req.bits.data := s1_slave_write_data

  // Other connections
  s2_nack := false.B
  io.memPort.req.bits.fcn := Mux(s1_slave_cmd === M_XRD, sodorConst.M_XRD, sodorConst.M_XWR)
  // The "&" at the end clears the MSB in case of "signed dword" request, which we treat as an unsigned one
  io.memPort.req.bits.typ := Mux(s1_slave_req_signed, s1_slave_req_size, s1_slave_req_size + 4.U(3.W) & 3.U)
}

// This class simply route all memory request that doesn't belong to the scratchpad
class SodorRequestRouter(cacheAddress: AddressSet)(implicit val conf: SodorConfiguration) extends Module {
  val io = IO(new Bundle() {
    val masterPort = new MemPortIo(data_width = conf.xprlen)
    val scratchPort = new MemPortIo(data_width = conf.xprlen)
    val corePort = Flipped(new MemPortIo(data_width = conf.xprlen))
  })

  val in_range = cacheAddress.contains(io.corePort.req.bits.addr)

  // Connect other signals
  io.masterPort.req.bits <> io.corePort.req.bits
  io.scratchPort.req.bits <> io.corePort.req.bits

  // Connect valid signal 
  io.masterPort.req.valid := io.corePort.req.valid & !in_range
  io.scratchPort.req.valid := io.corePort.req.valid & in_range

  // Mux ready and request signal
  io.corePort.req.ready := Mux(in_range, io.scratchPort.req.ready, io.masterPort.req.ready)
  io.corePort.resp := Mux(in_range, io.scratchPort.resp, io.masterPort.resp)
}
