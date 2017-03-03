/*
 * Copyright (C) 2017 Pluralsight, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hydra.kafka.producer

import hydra.core.ingest.HydraRequest
import hydra.core.ingest.IngestionParams.HYDRA_RECORD_KEY_PARAM
import hydra.core.protocol.ValidRequest
import org.scalatest.{FunSpecLike, Matchers}

/**
  * Created by alexsilva on 1/11/17.
  */
class StringRecordFactorySpec extends Matchers with FunSpecLike {

  describe("When using the StringRecordFactory") {

    it("handles valid strings") {
      val request = HydraRequest("test-topic","""{"name":"test"}""")
      val validation = StringRecordFactory.validate(request)
      validation shouldBe ValidRequest
    }

    it("builds") {
      val request = HydraRequest("test-topic", """{"name":"test"}""")
        .withMetadata(HYDRA_RECORD_KEY_PARAM -> "{$.name}")
      val msg = StringRecordFactory.build(request)
      msg.destination shouldBe "test-topic"
      msg.key shouldBe Some("test")
      msg.payload shouldBe """{"name":"test"}"""
    }
  }
}