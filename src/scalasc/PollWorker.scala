/*
 * PollWorker.scala
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

package scalasc
import org.snmp4j.smi.Address
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
import java.lang.Thread
import org.snmp4j.util._

import scala.collection.jcl._

class PollWorker extends Thread {

    override def run : Unit = {
        
        // setup listener
        val transport = new DefaultUdpTransportMapping()
        val snmp = new Snmp(transport)
        transport.listen()

        val targetAddress1 = GenericAddress.parse("udp:192.168.0.197/161")
        val target1 = new CommunityTarget(targetAddress1, new OctetString("public"))
        target1.setRetries(2)
        target1.setTimeout(1500)
        target1.setVersion(SnmpConstants.version2c)

        val targetAddress2 = GenericAddress.parse("udp:192.168.0.198/161")
        val target2 = new CommunityTarget(targetAddress2, new OctetString("public"))
        target2.setRetries(2)
        target2.setTimeout(1500)
        target2.setVersion(SnmpConstants.version2c)

        val ifDesc = new OID("1.3.6.1.2.1.2.2.1.2")
        val pdu1 = new PDU()
        pdu1.add(new VariableBinding(ifDesc))
        pdu1.setType(PDU.GETNEXT)

        val pdu2 = new PDU()
        pdu2.add(new VariableBinding(ifDesc))
        pdu2.setType(PDU.GETNEXT)

        val listener = new ResponseListener() {
            override def onResponse(event: ResponseEvent ) : Unit = {
                // Always cancel async request when response has been received
                // otherwise a memory leak is created! Not canceling a request
                // immediately can be useful when sending a request to a broadcast
                // address.
                event.getSource().asInstanceOf[Snmp].cancel(event.getRequest(), this)
                println("Thread " + Thread.currentThread.getName + " response from: " + event.getPeerAddress + " with id: " + event.getResponse.getRequestID)
               // println("Received response PDU is: "+event.getResponse())
                val r = event.getResponse()
                val vb = r.get(0)
                val v = vb.getVariable()
                print("{ ")
                v match {
                    case vx:TimeTicks => print(" timeticks \"" + vx.toString() + "\" ")
                    case vx:OctetString => print(" \"" + vx.toASCII('\0') + "\" ")
                    case _ => print (" " + v + " ")
                }
                println(" } ")
                
            }
        }
        snmp.sendPDU(pdu1, target1, null, listener)
        println("sent1 " + pdu1.getRequestID)
        snmp.sendPDU(pdu2, target2, null, listener)
        println("sent2 " + pdu2.getRequestID)

        println("sleep..")
        Thread.sleep(500000L);
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
