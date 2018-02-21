<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:java="java">

<!--
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

<xsl:output method="xml" version="1.0" encoding="UTF-8" indent="true" standalone="yes" />
<xsl:strip-space elements="*"/>

<!-- identity transform -->
<xsl:template match="@* | node()">
  <xsl:copy>
    <xsl:apply-templates select="node() | @*"/>
  </xsl:copy>
</xsl:template>

<!-- Required to ensure proper indentation for the comments in the config file -->
<xsl:template match="/comment()">
  <xsl:text>&#10;</xsl:text>
  <xsl:copy/>
</xsl:template>

<xsl:template match="solr">
  <xsl:text>&#10;</xsl:text>
  <xsl:copy>
    <xsl:apply-templates select="child::node()"/>
  </xsl:copy>
</xsl:template>

<xsl:template match="solr/solrcloud/str/@name">
  <xsl:if test=".='zkCredientialsProvider'">
    <xsl:message>* Replacing usage of zkCredientialsProvider with zkCredentialsProvider.</xsl:message>
  </xsl:if>
  <xsl:attribute name="name">
    <xsl:choose>
      <xsl:when test=".='zkCredientialsProvider'">zkCredentialsProvider</xsl:when>
      <xsl:otherwise><xsl:value-of select="."/></xsl:otherwise>
    </xsl:choose>
  </xsl:attribute>
</xsl:template>

<xsl:template match="solr/solrcloud/int[@name='hostPort']/text()">
  <xsl:choose>
    <xsl:when test="contains(.,'solr.port')"><xsl:message>* Replacing the name of system property for port configuration from solr.port to jetty.port</xsl:message></xsl:when>
    <xsl:otherwise></xsl:otherwise>
  </xsl:choose>
  <xsl:variable name="portStr" select="java:lang.String.new(string(.))"/>
  <xsl:variable name="targetStr" select="java:lang.String.new('solr.port')"/>
  <xsl:variable name="replaceStr" select="java:lang.String.new('jetty.port')"/>
  <xsl:value-of select="java:replaceFirst($portStr, $targetStr, $replaceStr)"/>
</xsl:template>

</xsl:stylesheet>
