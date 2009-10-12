/*
 * Main.scala
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
import org.snmp4j.TransportMapping
import org.snmp4j.mp._
import org.snmp4j.event._
import java.lang.Thread
import org.snmp4j.util._

import scala.collection.jcl._

object Main {

    def testPoll(): Unit = {
        val targetAddress = GenericAddress.parse("udp:127.0.0.1/161")
        val target = new CommunityTarget(targetAddress, new OctetString("public"))
        val transport = new DefaultUdpTransportMapping()
        val snmp = new Snmp(transport)
        snmp.listen()

        target.setRetries(2)
        target.setTimeout(2000L)
        target.setVersion(SnmpConstants.version2c)
        val utils = new TableUtils(snmp, new DefaultPDUFactory())
        val columns = Array[OID](
            new OID("1.3.6.1.2.1.2.2.1.2"), // ifdescr
              new OID("1.3.6.1.2.1.2.2.1.10"), // in octects
              new OID(".1.3.6.1.2.1.2.2.1.16"), // out octects
        )
        var table = new LinkedList[TableEvent](utils.getTable(target, columns, null, null).asInstanceOf[java.util.LinkedList[TableEvent]])
        //var table = utils.getTable(target, columns, null, null).asInstanceOf[LinkedList[TableEvent]]
  
        for ( val e:TableEvent  <- table )
        {
            // print(e)
            if ( e.getColumns()!=null )
                for ( vb:VariableBinding <- e.getColumns() )
                {
                    if ( vb!=null ) {
                        print("{ " + vb.getOid())
                        //+ " = " + vb.getVariable().toString() +
                        val v = vb.getVariable()
                        v match {
                            case vx:OctetString => print(" \"" + vx.toASCII('\0') + "\" ")
                            case _ => print (" " + v + " ")
                        }
                        print(" } ")
                    }
                    else { println("null variable here") }
                }
            else
                System.err.println("err: " + e.getErrorMessage())
            System.out.println()
        }
    }

    def testPollAsync(): Unit = {
        val targetAddress = GenericAddress.parse("udp:127.0.0.1/161")
        val target = new CommunityTarget(targetAddress, new OctetString("public"))
        val transport = new DefaultUdpTransportMapping()
        val snmp = new Snmp(transport)
        snmp.listen()

        target.setRetries(2)
        target.setTimeout(2000L)
        target.setVersion(SnmpConstants.version2c)
        val utils = new TableUtils(snmp, new DefaultPDUFactory())
        val columns = Array[OID](
            new OID("1.3.6.1.2.1.2.2.1.2"), // ifdescr
              new OID("1.3.6.1.2.1.2.2.1.10"), // in octects
              new OID("1.3.6.1.2.1.2.2.1.16"), // out octects
        )

        var tl = new TableListener() {
            private var finished = false

            override def isFinished(): boolean = { finished }

            override def finished(event:TableEvent ) : Unit = {
               println("finished")
               finished = true;
               event.getUserObject().synchronized {
                  event.getUserObject().notify();
               }
            }

            override def next(event:TableEvent) : boolean = {
                System.out.println("tick last Thread " + Thread.currentThread.getName)
                if ( event.getColumns()!=null )
                    for ( vb:VariableBinding <- event.getColumns() )
                    {
                        print("{ " + vb.getOid())
                        //+ " = " + vb.getVariable().toString() +
                        val v = vb.getVariable()
                        v match {
                            case vx:OctetString => print(" \"" + vx.toASCII('\0') + "\" ")
                            case _ => print (" " + v + " ")
                        }
                        print(" } ")
                    }
                else
                    System.err.println("err: " + event.getErrorMessage())
                true
            }
        }
        println("setup")
        var o = new Object();
        utils.getTable(target, columns, tl, o, null, null)
        println("waiting")
        while (!tl.isFinished()) {
            o.synchronized { o.wait(); }
         }
        println("wait finish")
    }
    /**
     * @param args the command line arguments
     */
    def main(args: Array[String]) :Unit = {

        // 127.0.0.1/161/public
        var index=0
        try{
            var workers = args.map[PollWorker]{ arg => index += 1; println(arg); new PollWorker(TargetSpec.createArray(arg), "THREAD:" + index, this) }
            workers.foreach( t => t.start)
            workers.foreach( t => t.join)
            println("main complete")
        }
        catch {
            case e: Exception => println(e)
        }
    }

}
