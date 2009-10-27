package poller
/*
 * newClass.scala
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

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
        dataSing = new Array[VariableBinding](colSing.length)
        bulkTiming = List[long]()
        singTiming = List[long]()
    }
    def doCompletionTimeout(worker:PollWorker, target:PollTarget) : Unit = {
        completion -= 1
        println("timeout completion for IP: " + target.targetAddress)
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
            case null => "null"
            case _ => v.toString()
        }
    }
    def recordBulkTiming(t:long):Unit={bulkTiming += t}
    def recordSingTiming(t:long):Unit={singTiming += t}

    def dumpdata(worker:PollWorker) : Unit = {
        var df = new SimpleDateFormat("yyyyMMddHHmmss");

        var name = new File("snmp_" + ip + "_" + df.format(new Date()))
        var f=new PrintStream(new BufferedOutputStream(new FileOutputStream(name)))
        try {
            
            f.println("#SINGLE SECTION: ")
            dataSing.foreach( v => f.println( v.getOid + "=" + variableToString(v.getVariable) ) )
            // kept this next "mapping" one just cause it was interesting
            // f.println(  dataSing.map( v => v.getOid + "=" + variableToString(v.getVariable) ).mkString("\n")  )
            f.println("#BULK SECTION: ")
            f.println("index," + colBulk.mkString(","))
            
            
            for ( index <- dataBulk(0).keys ) {
            	val omrec = dataBulk.map(  mm => variableToString( 
            		if (!mm.contains(index))
            		{ new Null() } 
            		else 
            		{mm(index).getVariable()} 
            	)  ).mkString(",")
                f.println(index + "," + omrec)
            }
            dataBulk.foreach( _.clear() )

            f.println("#Bulk timings: " + bulkTiming.map( _ / 1000000).mkString(",") )
            f.println("#Single timings: " + singTiming.map( _ / 1000000).mkString(",") )
            bulkTiming = List[long]()
            singTiming = List[long]()
        }
        catch {
          case x:Exception => println( x )
          	x.printStackTrace
        }
        finally {
            f.close()
        }
    }

}
