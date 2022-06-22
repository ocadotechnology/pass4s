credentials += Credentials(Path.userHome / ".sbt" / ".credentials")

addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.22")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
addSbtPlugin("org.typelevel" % "sbt-typelevel" % "0.4.12")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.3.2")
