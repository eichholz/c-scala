import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import sbt.internal.inc.classpath.ClasspathUtilities

import scala.sys.process.{Process, ProcessLogger}

def generateNative(classpath: Seq[Attributed[File]], baseDir: File, classDir: File, stream: TaskStreams) = {
  val processLogger = new ProcessLogger {
    var inError = false

    def buffer[T](f: => T) = f

    def err(s: => String): Unit = {
      println(s)
      if (s.toLowerCase.contains("error:")) inError = true
      if (s.toLowerCase.contains("warning:")) inError = false
      if (inError) stream.log.error(s) else ()
    }

    def info(s: => String) = ()

    override def out(s: => String): Unit = stream.log.out(s)
  }
  val jnisrcDir = baseDir / "jnisrc"
  val additionalFiles = Seq(jnisrcDir / "CLangNative.h", jnisrcDir / "CLangNative.c")
  val additionalMod = additionalFiles.map(_.lastModified).max
  val headerFile = jnisrcDir / "Native.h"
  val nativeLibrary = baseDir / "libshallow.jnilib"
  val oldHeaders = if (headerFile.exists) IO.read(headerFile) else ""
  // Generate headers for the native methods
  if (Process(s"javah -jni -o $headerFile ch.epfl.data.cscala.Native$$", Some(classDir)) ! processLogger != 0) throw new Incomplete(None)
  val newHeaders = IO.read(headerFile)

  if (!nativeLibrary.exists
    || oldHeaders != newHeaders
    || additionalMod > nativeLibrary.lastModified) {
    // User HeaderParser to generate the library's implementation
    stream.log.info("Generating native library...")
    val loader: ClassLoader = ClasspathUtilities.toLoader(classpath.map(_.data).map(_.getAbsoluteFile))
    val clazz = loader.loadClass("ch.epfl.data.cscala.generator.HeaderParser")
    val method = clazz.getMethod("main", classOf[Array[String]])
    val args = Array[String](headerFile.toString)
    method.invoke(null, args)

    // Compile the library
    val files = additionalFiles ++ Seq(headerFile, jnisrcDir / "Native.c")
    val glibLibs = Process("pkg-config --cflags glib-2.0 --libs glib-2.0", Some(jnisrcDir)) !! processLogger
    //    val jniLibs = "-I /System/Library/Frameworks/JavaVM.framework/Versions/Current/Headers -I /Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.9.sdk/System/Library/Frameworks/JavaVM.framework/Versions/A/Headers "
    val jniLibs = "-I /usr/lib/jvm/java-1.8.0-openjdk-1.8.0.201.b09-6.fc29.x86_64/include -I /usr/lib/jvm/java-1.8.0-openjdk-1.8.0.201.b09-6.fc29.x86_64/include/linux"
    stream.log.info("Compiling C code...")
    if (Process(s"clang $glibLibs $jniLibs -Wreturn-type -c ${files.mkString(" ")}", Some(jnisrcDir)) ! processLogger != 0) throw new Incomplete(None)
    stream.log.info("Creating object files...")
    if (Process(s"clang $glibLibs -dynamiclib -o libshallow.jnilib Native.o CLangNative.o", Some(jnisrcDir)) ! processLogger != 0) throw new Incomplete(None)
    IO.delete(List(jnisrcDir / "Native.o", jnisrcDir / "Native.h.gch", jnisrcDir / "Native.c", jnisrcDir / "CLangNative.h.gch", jnisrcDir / "CLangNative.o"))
    IO.move(jnisrcDir / "libshallow.jnilib", nativeLibrary)
  }
}

lazy val defaults = Seq(
  scalaVersion := "2.12.8",
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.7" % "test",
    "org.scala-lang" % "scala-compiler" % "2.12.8" % "optional",
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2"
  )
)

def formattingPreferences = {
  import scalariform.formatter.preferences._
  FormattingPreferences()
    .setPreference(RewriteArrowSymbols, false)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)
}

lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
  ScalariformKeys.preferences in Compile := formattingPreferences,
  ScalariformKeys.preferences in Test := formattingPreferences
)

lazy val root = (project in file("."))
  .settings(defaults)
  .aggregate(cScala)

lazy val cScala = (project in file("c-scala"))
  .settings(defaults)
  .settings(Seq(
    fork in Test := true,
    compile in Compile := {
      generateNative((dependencyClasspath in Compile).value, baseDirectory.value, (classDirectory in Compile).value, streams.value)
      (compile in Compile).value
    }

  ))
  .dependsOn(generator)

lazy val generator = (project in file("generator"))
  .settings(defaults)