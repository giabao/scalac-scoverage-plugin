package scoverage

import java.io.{File, FileNotFoundException}
import java.net.URL

import scala.collection.mutable.ListBuffer
import scala.tools.nsc.{Settings, Global}
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.transform.{Transform, TypingTransformers}

/** @author Stephen Samuel */
object ScoverageCompiler {

  val ScalaVersion = scala.util.Properties.versionNumberString.ensuring(_ != "")
  val ShortScalaVersion = ScalaVersion.substring(0, ScalaVersion.lastIndexOf('.'))

  def classPath = getScalaJars.map(_.getAbsolutePath) :+ sbtCompileDir.getAbsolutePath :+ runtimeClasses.getAbsolutePath

  def settings: Settings = {
    val s = new scala.tools.nsc.Settings
    s.Xprint.value = List("all")
    s.Yrangepos.value = true
    s.Yposdebug.value = true
    s.classpath.value = classPath.mkString(File.pathSeparator)

    val path = s"./scalac-scoverage-plugin/target/scala-$ShortScalaVersion/test-generated-classes"
    new File(path).mkdirs()
    s.d.value = path
    s
  }

  def default: ScoverageCompiler = {
    val reporter = new scala.tools.nsc.reporters.ConsoleReporter(settings)
    new ScoverageCompiler(settings, reporter)
  }

  def locationCompiler: LocationCompiler = {
    val reporter = new scala.tools.nsc.reporters.ConsoleReporter(settings)
    new LocationCompiler(settings, reporter)
  }

  private def getScalaJars: List[File] = {
    val scalaJars = List("scala-compiler", "scala-library", "scala-reflect")
    scalaJars.map(findScalaJar)
  }

  private def sbtCompileDir: File = {
    val dir = new File("./scalac-scoverage-plugin/target/scala-" + ShortScalaVersion + "/classes")
    if (!dir.exists)
      throw new FileNotFoundException(s"Could not locate SBT compile directory for plugin files [$dir]")
    dir
  }

  private def runtimeClasses: File = new File("./scalac-scoverage-runtime/target/scala-2.11/classes")

  private def findScalaJar(artifactId: String): File = findIvyJar("org.scala-lang", artifactId, ScalaVersion)

  private def findIvyJar(groupId: String, artifactId: String, version: String): File = {
    val userHome = System.getProperty("user.home")
    val sbtHome = userHome + "/.ivy2"
    val jarPath = sbtHome + "/cache/" + groupId + "/" + artifactId + "/jars/" + artifactId + "-" + version + ".jar"
    val file = new File(jarPath)
    if (!file.exists)
      throw new FileNotFoundException(s"Could not locate [$jarPath].")
    file
  }
}

class ScoverageCompiler(settings: scala.tools.nsc.Settings, reporter: scala.tools.nsc.reporters.Reporter)
  extends scala.tools.nsc.Global(settings, reporter) {

  def addToClassPath(groupId: String, artifactId: String, version: String): Unit = {
    settings.classpath.value = settings.classpath.value + File.pathSeparator + ScoverageCompiler
      .findIvyJar(groupId, artifactId, version)
      .getAbsolutePath
  }

  val instrumentationComponent = new ScoverageInstrumentationComponent(this)
  instrumentationComponent.setOptions(new ScoverageOptions())
  val testStore = new ScoverageTestStoreComponent(this)
  val validator = new PositionValidator(this)

  def compileSourceFiles(files: File*): ScoverageCompiler = {
    val command = new scala.tools.nsc.CompilerCommand(files.map(_.getAbsolutePath).toList, settings)
    new Run().compile(command.files)
    this
  }

  def writeCodeSnippetToTempFile(code: String): File = {
    val file = File.createTempFile("scoverage_snippet", ".scala")
    IOUtils.writeToFile(file, code)
    file.deleteOnExit()
    file
  }

  def compileCodeSnippet(code: String): ScoverageCompiler = compileSourceFiles(writeCodeSnippetToTempFile(code))
  def compileSourceResources(urls: URL*): ScoverageCompiler = {
    compileSourceFiles(urls.map(_.getFile).map(new File(_)): _*)
  }

  def assertNoCoverage() = assert(!testStore.sources.mkString(" ").contains(s"scoverage.Invoker.invoked"))

  def assertNMeasuredStatements(n: Int): Unit = {
    for ( k <- 1 to n ) {
      assert(testStore.sources.mkString(" ").contains(s"scoverage.Invoker.invoked($k,"),
        s"Should be $n invoked statements but missing #$k")
    }
    assert(!testStore.sources.mkString(" ").contains(s"scoverage.Invoker.invoked(${n + 1},"),
      s"Found statement ${n + 1} but only expected $n")
  }

  class PositionValidator(val global: Global) extends PluginComponent with TypingTransformers with Transform {

    override val phaseName: String = "scoverage-validator"
    override val runsAfter: List[String] = List("typer")
    override val runsBefore = List[String]("scoverage-instrumentation")

    override protected def newTransformer(unit: global.CompilationUnit): global.Transformer = new Transformer(unit)
    class Transformer(unit: global.CompilationUnit) extends TypingTransformer(unit) {

      override def transform(tree: global.Tree) = {
        global.validatePositions(tree)
        tree
      }
    }
  }

  class ScoverageTestStoreComponent(val global: Global) extends PluginComponent with TypingTransformers with Transform {

    val sources = new ListBuffer[String]

    override val phaseName: String = "scoverage-teststore"
    override val runsAfter: List[String] = List("dce")
    // deadcode
    override val runsBefore = List[String]("terminal")

    override protected def newTransformer(unit: global.CompilationUnit): global.Transformer = new Transformer(unit)
    class Transformer(unit: global.CompilationUnit) extends TypingTransformer(unit) {

      override def transform(tree: global.Tree) = {
        sources append tree.toString
        tree
      }
    }
  }
  override protected def computeInternalPhases(): Unit = {
    super.computeInternalPhases()
    val phs = List(
      validator -> "scoverage validator",
      instrumentationComponent -> "scoverage instrumentationComponent",
      testStore -> "scoverage teststore"
    )
    phs foreach (addToPhasesSet _).tupled
  }
}


