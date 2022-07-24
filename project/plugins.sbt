credentials += Credentials(Path.userHome / ".sbt" / ".credentials")

addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.4.1")
addSbtPlugin("org.typelevel" % "sbt-typelevel" % "0.4.13")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.3.2")
