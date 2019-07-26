addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.4.1")
addSbtPlugin("com.geirsson"    % "sbt-scalafmt"    % "1.5.1")
addSbtPlugin("com.47deg"       % "sbt-microsites"  % "0.9.1")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.23")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.9.0-M7"
