addSbtPlugin("org.wartremover"  % "sbt-wartremover"     % "2.4.2")
addSbtPlugin("com.geirsson"     % "sbt-scalafmt"        % "1.5.1")
addSbtPlugin("com.47deg"        % "sbt-microsites"      % "0.9.1")
addSbtPlugin("com.thesamet"     % "sbt-protoc"          % "0.99.23")
addSbtPlugin("com.eed3si9n"     % "sbt-assembly"        % "0.14.10")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.25")
addSbtPlugin("ch.epfl.scala"    % "sbt-bloop"           % "1.3.2")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.9.0-M7"
