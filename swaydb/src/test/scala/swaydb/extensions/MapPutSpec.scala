/*
 * Copyright (c) 2019 Simer Plaha (@simerplaha)
 *
 * This file is a part of SwayDB.
 *
 * SwayDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * SwayDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with SwayDB. If not, see <https://www.gnu.org/licenses/>.
 */

package swaydb.extensions

import org.scalatest.OptionValues._
import swaydb.IOValues._
import swaydb.api.TestBaseEmbedded
import swaydb.core.RunThis._
import swaydb.data.util.StorageUnits._
import swaydb.serializers.Default._

class MapPutSpec0 extends MapPutSpec {
  val keyValueCount: Int = 1000

  override def newDB(): Map[Int, String] =
    swaydb.extensions.persistent.Map[Int, String, Nothing](dir = randomDir).right.value.right.value
}

class MapPutSpec1 extends MapPutSpec {

  val keyValueCount: Int = 10000

  override def newDB(): Map[Int, String] =
    swaydb.extensions.persistent.Map[Int, String, Nothing](randomDir, mapSize = 1.byte).right.value.right.value
}

class MapPutSpec2 extends MapPutSpec {

  val keyValueCount: Int = 100000

  override def newDB(): Map[Int, String] =
    swaydb.extensions.memory.Map[Int, String, Nothing](mapSize = 1.byte).right.value.right.value
}

class MapPutSpec3 extends MapPutSpec {
  val keyValueCount: Int = 100000

  override def newDB(): Map[Int, String] =
    swaydb.extensions.memory.Map[Int, String, Nothing]().right.value.right.value
}

sealed trait MapPutSpec extends TestBaseEmbedded {

  val keyValueCount: Int

  def newDB(): Map[Int, String]

  "Root" should {
    "Initialise a RootMap & SubMap from Root" in {
      val db = newDB()

      val rootMap = db.maps.put(1, "rootMap").right.value
      val firstMap = rootMap.maps.put(2, "first map").right.value

      firstMap.put(3, "three").right.value
      firstMap.put(4, "four").right.value
      firstMap.put(5, "five").right.value
      firstMap.put(4, "four again").right.value

      firstMap
        .stream
        .materialize.runRandomIO.right.value should contain inOrderOnly((3, "three"), (4, "four again"), (5, "five"))

      db.closeDatabase().right.value
    }

    "Initialise a RootMap & 2 SubMaps from Root" in {
      val db = newDB()

      def insert(firstMap: Map[Int, String]) = {
        firstMap.put(3, "three").right.value
        firstMap.put(4, "four").right.value
        firstMap.put(5, "five").right.value
        firstMap.put(4, "four again").right.value
      }

      val firstMap = db.maps.put(1, "first map").right.value

      val secondMap = firstMap.maps.put(2, "second map").right.value
      insert(secondMap)

      val thirdMap = firstMap.maps.put(3, "third map").right.value
      insert(thirdMap)

      secondMap
        .stream
        .materialize.runRandomIO.right.value should contain inOrderOnly((3, "three"), (4, "four again"), (5, "five"))

      thirdMap
        .stream
        .materialize.runRandomIO.right.value should contain inOrderOnly((3, "three"), (4, "four again"), (5, "five"))

      db.closeDatabase().right.value
    }

    //    "Initialise 2 RootMaps & 2 SubMaps under each SubMap" in {
    //      val db = newDB()
    //
    //      def insertInMap(firstMap) = {
    //        firstMap.put(3, "three").runIO
    //        firstMap.put(4, "four").runIO
    //        firstMap.put(5, "five").runIO
    //        firstMap.put(4, "four again").runIO
    //      }
    //
    //      def insertInRoot(rootMap: Root[Int, String]) = {
    //        val firstMap = rootMap.maps.put(2, "first map").runIO
    //        insertInMap(firstMap)
    //
    //        val secondMap = rootMap.maps.put(3, "second map").runIO
    //        insertInMap(secondMap)
    //      }
    //
    //      insertInRoot(db)
    //
    //      rootMap1.maps.put(2, "first map").runIO.toSeq.right.value should contain inOrderOnly((3, "three"), (4, "four again"), (5, "five"))
    //      rootMap1.maps.put(3, "second map").runIO.toSeq.right.value should contain inOrderOnly((3, "three"), (4, "four again"), (5, "five"))
    //      rootMap2.maps.put(2, "first map").runIO.toSeq.right.value should contain inOrderOnly((3, "three"), (4, "four again"), (5, "five"))
    //      rootMap2.maps.put(3, "second map").runIO.toSeq.right.value should contain inOrderOnly((3, "three"), (4, "four again"), (5, "five"))
    //    }

    "putOrGet spec" in {
      val db = newDB()

      val map = db.maps.getOrPut(1, "firstMap").right.value
      map.exists().right.value shouldBe true
      map.getValue().right.value.value shouldBe "firstMap"

      val mapAgain = db.maps.getOrPut(1, "firstMap put again").right.value
      mapAgain.exists().right.value shouldBe true
      //value does not change as the map already exists.
      mapAgain.getValue().right.value.value shouldBe "firstMap"

      db.closeDatabase().right.value
    }

    "Initialise 5 nested maps with 2 elements in each map" in {
      val db = newDB()

      val rootMap = db.maps.put(1, "rootMap1").right.value

      val subMap1 = rootMap.maps.put(2, "sub map 2").right.value
      subMap1.put(1, "one").right.value
      subMap1.put(2, "two").right.value

      val subMap2 = subMap1.maps.put(3, "sub map three").right.value
      subMap2.put(3, "three").right.value
      subMap2.put(4, "four").right.value

      val subMap3 = subMap2.maps.put(5, "sub map five").right.value
      subMap3.put(5, "five").right.value
      subMap3.put(6, "six").right.value

      val subMap4 = subMap3.maps.put(7, "sub map seven").right.value
      subMap4.put(7, "seven").right.value
      subMap4.put(8, "eight").right.value

      subMap1.stream.materialize.runRandomIO.right.value should contain inOrderOnly((1, "one"), (2, "two"))
      subMap1.maps.stream.materialize.runRandomIO.right.value should contain only ((3, "sub map three"))
      subMap2.stream.materialize.runRandomIO.right.value should contain inOrderOnly((3, "three"), (4, "four"))
      subMap2.maps.stream.materialize.runRandomIO.right.value should contain only ((5, "sub map five"))
      subMap3.stream.materialize.runRandomIO.right.value should contain inOrderOnly((5, "five"), (6, "six"))
      subMap3.maps.stream.materialize.runRandomIO.right.value should contain only ((7, "sub map seven"))
      subMap4.stream.materialize.runRandomIO.right.value should contain inOrderOnly((7, "seven"), (8, "eight"))

      db.closeDatabase().right.value
    }

    "Initialise 5 sibling maps with 2 elements in each map" in {
      val db = newDB()

      val rootMap = db.maps.put(1, "rootMap1").right.value

      val subMap1 = rootMap.maps.put(2, "sub map 2").right.value
      subMap1.put(1, "one").right.value
      subMap1.put(2, "two").right.value

      val subMap2 = rootMap.maps.put(3, "sub map three").right.value
      subMap2.put(3, "three").right.value
      subMap2.put(4, "four").right.value

      val subMap3 = rootMap.maps.put(5, "sub map five").right.value
      subMap3.put(5, "five").right.value
      subMap3.put(6, "six").right.value

      val subMap4 = rootMap.maps.put(7, "sub map seven").right.value
      subMap4.put(7, "seven").right.value
      subMap4.put(8, "eight").right.value

      subMap1.stream.materialize.runRandomIO.right.value should contain inOrderOnly((1, "one"), (2, "two"))
      subMap2.stream.materialize.runRandomIO.right.value should contain inOrderOnly((3, "three"), (4, "four"))
      subMap3.stream.materialize.runRandomIO.right.value should contain inOrderOnly((5, "five"), (6, "six"))
      subMap4.stream.materialize.runRandomIO.right.value should contain inOrderOnly((7, "seven"), (8, "eight"))

      db.closeDatabase().right.value
    }
  }
}
