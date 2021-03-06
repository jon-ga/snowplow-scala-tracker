/*
 * Copyright (c) 2015 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.scalatracker

// Scala
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.control.NonFatal

// Akka
import akka.util.Timeout

// Spray
import spray.http._
import spray.client.pipelining._

// json4s
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

// This library
import emitters.RequestUtils

/**
 * Trait with parsing EC2 meta data logic
 * http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-metadata.html
 */
object Ec2Metadata {
  import RequestUtils.system
  import system.dispatcher
  val shortTimeout = 10.seconds
  implicit val timeout = Timeout(shortTimeout)
  val pipeline: HttpRequest => Future[HttpResponse] = sendReceive

  val instanceIdentitySchema = "iglu:com.amazon.aws.ec2/instance_identity_document/jsonschema/1-0-0"
  val instanceIdentityUri = "http://169.254.169.254/latest/dynamic/instance-identity/document/"

  private var contextSlot: Option[SelfDescribingJson] = None

  /**
   * Get context stored in mutable variable
   *
   * @return some context or None in case of any error or not completed request
   */
  def context: Option[SelfDescribingJson] = contextSlot

  /**
   * Set callback on successful instance identity GET request
   */
  def initializeContextRequest: Unit = {
    getInstanceContextFuture.onSuccess {
      case json: SelfDescribingJson => contextSlot = Some(json)
    }
  }

  /**
   * Tries to make blocking request to EC2 instance identity document
   * On EC2 request takes ~6ms, while on non-EC2 box it blocks thread for 3 second
   *
   * @return some context or None in case of any error including 3 sec timeout
   */
  def getInstanceContextBlocking: Option[SelfDescribingJson] =
    try {
      Some(Await.result(getInstanceContextFuture, 3 seconds))
    }
    catch {
      case NonFatal(_) => None
    }

  /**
   * Tries to GET self-describing JSON with instance identity
   * or timeout after 10 seconds
   *
   * @return future JSON with identity data
   */
  def getInstanceContextFuture: Future[SelfDescribingJson] =
    getInstanceIdentity.map(SelfDescribingJson(instanceIdentitySchema, _))

  /**
   * Tries to GET instance identity document for EC2 instance
   *
   * @return future JSON object with identity data
   */
  def getInstanceIdentity: Future[JObject] = {
    val instanceIdentityDocument = pipeline(Get(instanceIdentityUri))
    instanceIdentityDocument.map(_.entity.asString).map { (resp: String) =>
      parseOpt(resp) match {
        case Some(jsonObject: JObject) => {
          val prepared = prepareEc2Context(jsonObject)
          if (prepared.values.keySet.size == 0) { throw new Exception("Document contains no known keys") }
          else { prepared }
        }
        case _ =>
          throw new Exception("Document can not be parsed")
      }
    }
  }

  /**
   * Recursively parse AWS EC2 instance metadata to get whole metadata
   *
   * @param url full url to the endpoint (usually http://169.254.169.254/latest/meta-data/)
   * @return future JSON object with metadata
   */
  def getMetadata(url: String): Future[JObject] = {
    val key = url.split("/").last
    if (!url.endsWith("/")) { // Leaf
      getContent(url).map { value => key -> JString(value) }
    } else {                  // Node
      val sublinks = getContents(url)
      val subnodes: Future[List[JObject]] = sublinks.flatMap { links =>
        Future.sequence { links.map { link => getMetadata(url + link) } }
      }
      val mergedObject = subnodes.map { _.fold(JObject(Nil))(_.merge(_)) }
      mergedObject.map(key -> _)
    }
  }

  // URL regex to for `transformUrl`
  private val publicKey = ".*/latest/meta-data/public-keys/(\\d+)\\=[A-Za-z0-9-_]+$".r

  /**
   * Handle URL which should be handled in different ways
   * e.g. we can't GET public-keys/0-key-name, we should change it to public-keys/0
   * to get data
   *
   * @param url current URL
   * @return modified URL if we're trying to get on of special cases
   */
  def transformUrl(url: String): String = url match {
    case publicKey(i) => (url.split("/").dropRight(1) :+ i).mkString("/") + "/"
    case _            => url
  }

  /**
   * Get URL content (for leaf-link)
   *
   * @param url leaf URL (without slash at the end)
   * @return future value
   */
  private def getContent(url: String): Future[String] =
    pipeline(Get(url)).map(_.entity.asString)

  /**
   * Get content of node-link
   *
   * @param url node url (with slash at the end)
   * @return future list of sublinks
   */
  private def getContents(url: String): Future[List[String]] =
    getContent(url).map(_.split('\n').toList)

  // all keys of current instance identity schema
  private val instanceIdentityKeys = Set(
    "architecture", "accountId", "availabilityZone", "billingProducts",
    "devpayProductCodes", "imageId", "instanceId", "instanceType", "kernelId",
    "pendingTime", "privateIp", "ramdiskId", "region", "version")

  /**
   * Make sure EC2 context contains only keys known
   * at iglu:com.amazon.aws.ec2/instance_identity_document
   *
   * @param context JSON object with EC2 context
   * @return true if object is context
   */
  private def prepareEc2Context(context: JObject): JObject =
    context.filterField {
      case (key, _) => instanceIdentityKeys.contains(key)
    }
}
