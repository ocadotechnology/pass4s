credentials += Credentials(Path.userHome / ".sbt" / ".credentials")

addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.4.4")
addSbtPlugin("org.typelevel" % "sbt-typelevel" % "0.6.6")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.4.0")
