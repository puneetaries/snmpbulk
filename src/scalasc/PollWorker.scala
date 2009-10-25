/*
 * PollWorker.scala
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

//import scala.collection.jcl._

case class TargetSpec(ip:String, port:Int, community:String)
// multi constructor pattern
object TargetSpec {
    def apply(targetString:String) = {
        var parts=targetString.split("/")
        if ( parts.length<3 )
            throw new RuntimeException("Cannot parse target string: " + targetString)
        new TargetSpec(parts(0), parts(1).toInt, parts(2))
    }
    def createArray(targetString:String) : List[TargetSpec] = {
        var targetList = List[TargetSpec]()
        targetString.split(",").foreach{ target =>
            var parts=target.split("/")
            if ( parts.length<3 )
                throw new RuntimeException("Cannot parse target string: " + targetString)
printf("%s %d %s\n",parts(0), parts(1).toInt, parts(2))
            targetList += new TargetSpec(parts(0), parts(1).toInt, parts(2))
        }
        targetList
    }
}

class PollWorker(targetset:List[TargetSpec], name:String, intervalSecs:long, iolock:Object) extends Thread {

    case class OutStanding(requestId:int, baseOid:OID, pollTarget:PollTarget, colBulkIndex:int, colSingIndex:int, lastTime:long)

    val listenerBulk = new ReceiverBulk
    val listenerSing = new ReceiverSingle
    var targets =Map[String,PollTarget]()
    var outStanding = new ConcurrentHashMap[Int,OutStanding]()
    var completed = new CountDownLatch(targetset.size)
    val transport = new DefaultUdpTransportMapping()
    transport.setThreadName(name)
    val snmp = new Snmp(transport)



    class MyTimeoutPolicy extends AnyRef with TimeoutModel {
        var to = 1000L
        var retry = 1
        override def getRequestTimeout(totalNumberOfRetries:Int, targetTimeout:Long) : long = { 
//            printf("##########################  getRequestTimeout total: %d  targetTimeout: %d\n", totalNumberOfRetries, targetTimeout)
            to*(retry+1)
        }

        override def getRetryTimeout(retryCount:Int, totalNumberOfRetries:Int, targetTimeout:Long) : Long = {
  //          printf("##########################  getRetryTimeout retry: %d  total: %d  targetTimeout: %d\n", retryCount, totalNumberOfRetries, targetTimeout)
//            if ( retryCount > retry )
//                -1L
//            else
                to
        }
    }

     def sendInitialPDU(pollTarget:PollTarget) : Unit = {
        var index=0
        for ( oid <- pollTarget.colBulk) {
            var currPDU = new PDU()
            currPDU.add(new VariableBinding(oid))
            currPDU.setMaxRepetitions(5)
            currPDU.setType(PDU.GETBULK)
            snmp.sendPDU(currPDU, pollTarget.target, null, listenerBulk)
            outStanding.put(currPDU.getRequestID.toInt, new OutStanding(currPDU.getRequestID.toInt,oid,pollTarget, index, -1,System.nanoTime))
            println("sent bulk " + currPDU.getRequestID.toInt + " for " + oid)
            index += 1
        }
        index=0
        for ( oid <- pollTarget.colSing) {
            var currPDU = new PDU()
            currPDU.add(new VariableBinding(oid))
            currPDU.setType(PDU.GETNEXT)
            snmp.sendPDU(currPDU, pollTarget.target, null, listenerSing)
            outStanding.put(currPDU.getRequestID.toInt, new OutStanding(currPDU.getRequestID.toInt,oid,pollTarget, -1, index,System.nanoTime))
            println("sent single " + currPDU.getRequestID.toInt + " for " + oid)
            index += 1
        }
    }
    def sendNextPDU(pollTarget:PollTarget, nextOid:OID, baseOid:OID, index:Int) : Unit = {
        var currPDU = new PDU()
        currPDU.add(new VariableBinding(nextOid))
        currPDU.setMaxRepetitions(5)
        currPDU.setType(PDU.GETBULK)
        snmp.sendPDU(currPDU, pollTarget.target, null, listenerBulk)
        outStanding.put(currPDU.getRequestID.toInt, new OutStanding(currPDU.getRequestID.toInt,baseOid,pollTarget, index, -1,System.nanoTime))
        println("sent " + currPDU.getRequestID + " for " + nextOid)
    }

    class ReceiverSingle extends AnyRef with ResponseListener {
        override def onResponse(event: ResponseEvent) : Unit = {
            // Always cancel async request when response has been received
            // otherwise a memory leak is created! Not canceling a request
            // immediately can be useful when sending a request to a broadcast
            // address.
            try {
                event.getSource().asInstanceOf[Snmp].cancel(event.getRequest(), this)
/*
                println("Thread " + Thread.currentThread.getName +
                    " response from: " + event.getPeerAddress +
                    " with id: " + (if (event.getResponse == null ) -1 else event.getResponse.getRequestID)  )
*/
            }
            catch { case e:Exception => e.printStackTrace }
            val out = outStanding.remove(event.getRequest.getRequestID.toInt)
            if ( out == null ) {
                println("untracked response: put useful info here")
                return
            } 
            val target = out.pollTarget
            val responseTime = System.nanoTime - out.lastTime
            if ( event != null ) {
                val r = event.getResponse()
                if ( r != null && r.size == 1 ) {
                    //println("GOT single: " + r.get(0).getVariable)
                    val ip = event.getPeerAddress.toString
                    target.dataSing(out.colSingIndex)=r.get(0)
                    target.doCompletion(PollWorker.this)
                    target.recordSingTiming(responseTime)
                } else {
                    out.pollTarget.doCompletionTimeout(PollWorker.this)
                }
            } else {
                target.doCompletionTimeout(PollWorker.this)
            }

        }
    }
    class ReceiverBulk extends AnyRef with ResponseListener {

        def isExpectedOid(event: ResponseEvent, oid:OID) : Option[OID] = {
            var partOf = true
            val expectedOid = event.getRequest.get(0).getOid()
            if ( oid.size != expectedOid.size-1 ) None
            if ( !oid.startsWith(expectedOid) ) None

            new Some[OID](oid.nextPeer())
        }

        override def onResponse(event: ResponseEvent) : Unit = {
            // Always cancel async request when response has been received
            // otherwise a memory leak is created! Not canceling a request
            // immediately can be useful when sending a request to a broadcast
            // address.
            try {
                event.getSource().asInstanceOf[Snmp].cancel(event.getRequest(), this)

                println("Thread " + Thread.currentThread.getName +
                    " response from: " + event.getPeerAddress +
                    " with id: " + (if (event.getRequest.getRequestID == null ) -1 else event.getRequest.getRequestID)  )

            }
            catch { case e:Exception => e.printStackTrace }
            val out = outStanding.remove(event.getRequest.getRequestID.toInt)
            val responseTime = System.nanoTime - out.lastTime
            // println("Received response PDU is: "+event.getResponse())
            if ( event != null ) {
                val r = event.getResponse()
                if ( r != null && r.size > 0 ) {

                    val ip = event.getPeerAddress.toString
                    val target = out.pollTarget

                    var jumpedRails=false
                    var lastoid=new OID("")
//                    println("got response size: " + r.size)
                    var i = 0
                    // slight violation of encapsulation but faster this way since we
                    // must walk the result the result for jumping
                    var d = target.dataBulk(out.colBulkIndex)
                    //var d = dd(out.colIndex)
                    for ( i <- 0 to r.size-1) {
                        val vb = r.get(i)
                        val oid = vb.getOid
                        lastoid = oid
                        val v = vb.getVariable()
                        // make sure we have not jumped the rails...

//                        println("this oid: " + oid + " base oid: " + out.baseOid)
                        if ( oid.startsWith(out.baseOid) ) {
                            val lastOidIndex = vb.getOid().last()
                            d(lastOidIndex)=vb
/*
                            print("i: " + i + "{ ")
                            v match {
                                case vx:TimeTicks => print(" timeticks \"" + vx.toString() + "\" ")
                                case vx:OctetString => print(" \"" + vx.toASCII('\0') + "\" ")
                                case _ => print (" " + v + " ")
                            }
                            println(" } ")
*/
                        } else {
                            println("JUMPED")
                            jumpedRails=true
                        }

                    }
                    target.recordBulkTiming(responseTime)

                    if ( !jumpedRails) {
                        // we are not done yet so get next oid
                        val nextoid = out.baseOid.clone.asInstanceOf[OID]
                        nextoid.append(lastoid.last)
                        sendNextPDU(target, nextoid, out.baseOid, out.colBulkIndex)
                        println("#### NEXT")
                    } else {
                        target.doCompletion(PollWorker.this)
                        println("##### DONE")
                    }
                } else {
                    println("empty response")
                    out.pollTarget.doCompletionTimeout(PollWorker.this)
                }

            } else {
println("calling TO complete")
                out.pollTarget.doCompletionTimeout(PollWorker.this)
            }
        }
    }

    override def run : Unit = {
        
        // setup listener
        snmp.setTimeoutModel(new MyTimeoutPolicy)
        // spawns the actual listening thread

        transport.listen()
        transport.setThreadName(name)

        // setup targets

        val pollsetBulk:Array[OID] = Array[OID](new OID("1.3.6.1.2.1.2.2.1.2"),  new OID("1.3.6.1.2.1.2.2.1.10"),  new OID("1.3.6.1.2.1.2.2.1.16"))
        val pollsetSing:Array[OID] = Array[OID](new OID(".1.3.6.1.2.1.1.3"), new OID(".1.3.6.1.2.1.1.1"))

        targets = Map()
        for ( target <- targetset ) {
            targets += target.ip + "/" + target.port.toString -> new PollTarget(target.ip, pollsetBulk, pollsetSing)
        }
//        targets = Map("192.168.0.198/161" -> new PollTarget("192.168.0.198", pollsetBulk, pollsetSing),
//                      "127.0.0.1/161"     -> new PollTarget("127.0.0.1", pollsetBulk, pollsetSing) )

        //val pollset:Array[OID] = Array[OID](new OID("1.3.6.1.2.1.2.2.1.2"))
        //targets = Map("127.0.0.1/161" -> new PollTarget("127.0.0.1", pollset) )

        //val targets = Map(1 -> 1, 1 -> 2 )
        while (true ) {
            
            for ( pt <- targets.values ) {
                pt.init
                sendInitialPDU(pt)
            }
            println("waiting on latch...")
            completed.await()
            println("latch done -----------------------------------")
            val now = System.currentTimeMillis
            val sleeptime = intervalSecs*1000 -  now % (intervalSecs*1000)

            println("sleeping for: " + sleeptime)
            Thread.sleep(sleeptime)
            completed = new CountDownLatch(targetset.size)
        }
//Thread.sleep(50000L)
        0
    }

    def spawnThread : Unit = {
        var t:Thread = new Thread() {
            override def run : Unit = { println({"hi"}) }
        }
        t.start()
        Thread.`yield`()
        println("started")
        t.join()
        println("done")
    }
    
}
