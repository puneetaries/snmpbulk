/*
 * XmlCtx.scala
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

package scalasc
import java.io.File
import java.util.Date
import java.util.Properties
import scala.xml.NodeSeq
import scala.util.matching.Regex
import java.lang.System.currentTimeMillis
import scala.collection.mutable.HashSet
import scala.collection.mutable.Set

class ConfigException(msg:String,cause:Exception) extends java.lang.Exception(msg, cause)
{
    def this(msg:String) = this(msg,null)
}
//
//  Read a XML configuration files with convenience methods
//
object XmlCtx
{
    private var _root:XmlCtx = null
    private var _baseDir:File = null
    def init(file:File) : XmlCtx =
    {
        if ( root == null )
        {
            val rootSeq = scala.xml.XML.loadFile(file);
            val topele = rootSeq \\ "config"
            _root = new XmlCtx(topele, "config")
            if ( _root.is("basedir") )
                _baseDir = _root.getFile("basedir")
            else if ( System.getenv("GMS_HOME") != null )
                _baseDir = new File(System.getenv("GMS_HOME"))
            else
            {
                println("WARNING: using current directory as the base directory")
                _baseDir = new File("./")
            }
        }
        _root
    }

    def root() : XmlCtx = _root
    def baseDir() : File = _baseDir
}

class XmlCtx (_here:NodeSeq, _fullpath:String)
{
    var here = _here
    var fullpath = _fullpath

    def this(parent:XmlCtx, childpath:String)
    {
        this(parent.here \ childpath, parent.fullpath + "/" + childpath)
        if ( here.length!=1 ) throw new ConfigException("Expecting a single entry for a context constructor: " + childpath + " but got " + here.length)
    }

    def getContext(childpath:String) : XmlCtx = {
        new XmlCtx(this, childpath)
    }

    def is(childpath:String) : boolean = (here \ childpath).length>0

    def getString(childpath:String)  : String  = (expectOne(childpath)).text
    def getString()  : String  = here.text

    def getBoolean(childpath:String) : boolean = strToBoolean(getString(childpath))
    def getBoolean() : boolean = strToBoolean(getString())

    def getLong(childpath:String) : Long = getString(childpath).toLong
    def getLong() : Long = getString().toLong

    def getFile(childpath:String) : File = checkFile(getString(childpath))
    def getFile() : File = checkFile(getString())

    val reInterval = """^\s*(\d+)\s*([A-z]+)\s*$""".r

    def strToBoolean(str:String) : boolean =
    {
        if ( str.length < 1 )
            throw new ConfigException("boolean setting is empty string or null")

        str(0) match {
            case '1' | 't' | 'T' => true
            case _ => false
        }
    }

    def strToInterval(str:String) : long =
    {
        try {
            var reInterval(numstr, inttype) = str
            val num =  {inttype(0) match {
                    case 'd' | 'd' => numstr.toLong * 1000L * 3600L * 24L
                    case 'H' | 'h' => numstr.toLong * 1000L * 3600L
                    case 'M' | 'm' => numstr.toLong * 1000L * 60L
                    case 's' | 's' => numstr.toLong * 1000L
                    case _ => throw new ConfigException("Interval unit: " + inttype + " is not understood")
                }
            }
            num
        } catch {
            case e:MatchError => throw new ConfigException("Interval setting: " + str + " cannot be parsed", e)
            case e: Exception => throw new ConfigException("Error reading Interval setting", e)
        }
    }

    def getInterval(childpath:String) : Long = strToInterval(getString(childpath))
    def getInterval() : Long = strToInterval(getString())

    def getList(childpath:String)  : Seq[XmlCtx]  = (expectList(childpath)).map(n => new XmlCtx(n,childpath))
    def getListNullable(childpath:String)  : Seq[XmlCtx]  = (here \ childpath).map(n => new XmlCtx(n,childpath))

    def checkFile(filename:String) : File =
    {
        var file:File=null
        if ( filename.startsWith("/") ||  filename.startsWith("\\") || (filename.length >= 3 && filename.indexOf(2) == '\\') )
        {
            file = new File(filename)
        }
        else
        {
            file = new File(XmlCtx.baseDir, filename)
        }

        if ( !file.exists() )
            throw new ConfigException("File: " + file + " does not exist")
        file
    }

    def expectOne(childpath:String) : NodeSeq =
    {
        var o = here \ childpath
        if (o.length<1) throw  new ConfigException("Missing child error, path: " + fullpath + " child: " + childpath)
        if (o.length>1) throw  new ConfigException("Multiple children error - expected one instance at, path: " + fullpath + " child: " + childpath)
        o
    }
    def expectList(childpath:String) : NodeSeq =
    {
        var o = here \ childpath
        if (o.length<1) throw  new ConfigException("Missing child error, path: " + fullpath + " child: " + childpath)
        o
    }
}
