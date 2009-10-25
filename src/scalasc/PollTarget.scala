/*
 * newClass.scala
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

package scalasc
import org.snmp4j.smi.Address
import org.snmp4j.TimeoutModel
import org.snmp4j.smi._
import org.snmp4j.transport.DefaultUdpTransportMapping
import org.snmp4j.util.DefaultPDUFactory
import org.snmp4j.util.PDUFactory
import org.snmp4j.util.TableEvent
import org.snmp4j.util.TableUtils
import org.snmp4j.CommunityTarget
import org.snmp4j.Snmp
import org.snmp4j.PDU
import org.snmp4j.TransportMapping
import org.snmp4j.mp._
import org.snmp4j.event._
import org.snmp4j.DefaultTimeoutModel
import java.lang.Thread
import org.snmp4j.util._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.text.SimpleDateFormat
import java.util.Date
import java.io._


case class PollTarget(ip:String, colBulk:Array[OID], colSing:Array[OID]) {
    var dataBulk = new Array[scala.collection.mutable.LinkedHashMap[Int,VariableBinding]](colBulk.length)
    var dataSing = new Array[VariableBinding](colSing.length)
    var completion=0 //=colBulk.length + colSing.length
    var bulkTiming = List[long]()
    var singTiming = List[long]()
    val targetAddress = GenericAddress.parse("udp:" + ip + "/161")
    val target = new CommunityTarget(targetAddress, new OctetString("public"))
    //init()
    def init() : Unit = {
        completion = colBulk.length + colSing.length
        target.setRetries(2)
        target.setVersion(SnmpConstants.version2c)
        for( i <- 0 to colBulk.length-1) { dataBulk(i) = new scala.collection.mutable.LinkedHashMap[Int,VariableBinding]() }
    }
    def doCompletionTimeout(worker:PollWorker) : Unit = {
        completion -= 1
println("timeout completion")
        worker.completed.countDown()  // giveup
    }
    def doCompletion(worker:PollWorker) : Unit = {
        completion -= 1
        if ( completion== 0) {
            /*worker.iolock.synchronized */ {
                worker.completed.countDown()
                dumpdata(worker)
                println("###################### count" + worker.completed.getCount)
            }
        }
    }
    def variableToString(v:Variable):String =
    {
        v match {
            case vx:TimeTicks => vx.toString()
            case vx:OctetString => vx.toASCII(' ')
            case _ => v.toString()
        }
    }
    def recordBulkTiming(t:long):Unit={bulkTiming += t}
    def recordSingTiming(t:long):Unit={singTiming += t}

    def dumpdata(worker:PollWorker) : Unit = {
        var df = new SimpleDateFormat("yyyyMMddHHmm");

        var name = new File("snmp_" + ip + "_" + df.format(new Date()))
        var f=new PrintStream(new BufferedOutputStream(new FileOutputStream(name)))
        try {
            
            for( i <- 0 to colSing.length-1) {
                f.print(colSing(i) + "=")
                f.print(variableToString(dataSing(i).getVariable))
                dataSing(i)=null
            }
            for(i <- 0 to colBulk.length-1){
                f.print(colBulk(i))
                if ( i!=colBulk.length-1) print(',')
            }
            for ( index <- dataBulk(0).keys ) {
                print(index)
                print(',')
                for(i <- dataBulk.indices){
                    val v=dataBulk(i)(index).getVariable()
                    print(variableToString(v))
                    print(',')
                }
                println()
//                    for ( index <- m.keys) {
//                        val v = m(index).getVariable()
//                        printVariable(f,v)
//                    }
//                }
            }
            for(i <- 0 to colBulk.length-1)
                dataBulk(i).clear

            f.println("Bulk timings:")
            for(l <- bulkTiming) f.print(" " + l/(1000000))
            f.println()
            f.println("Single timings: ")
            for(l <- singTiming) f.print(" " + l/1000000)
            f.println()
        }
        finally {
            f.close()
        }
    }

}
