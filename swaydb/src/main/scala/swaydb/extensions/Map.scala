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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with SwayDB. If not, see <https://www.gnu.org/licenses/>.
 */

package swaydb.extensions

import swaydb.Error.API.ExceptionHandler
import swaydb.IO._
import swaydb.data.accelerate.LevelZeroMeter
import swaydb.data.compaction.LevelMeter
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice
import swaydb.extensions.stream.{MapKeysStream, MapStream}
import swaydb.serializers.Serializer
import swaydb.{Done, From, IO, Prepare}

import scala.concurrent.duration.{Deadline, FiniteDuration}

private[extensions] object Map {
  def apply[K, V](map: swaydb.Map[Key[K], Option[V], Nothing, IO.ApiIO],
                  mapKey: Seq[K])(implicit keySerializer: Serializer[K],
                                  valueSerializer: Serializer[V],
                                  mapKeySerializer: Serializer[Key[K]],
                                  optionValueSerializer: Serializer[Option[V]],
                                  keyOrder: KeyOrder[Slice[Byte]]): Map[K, V] =
    new Map[K, V](
      mapKey = mapKey,
      map = map
    )

  /**
   * Creates the entries range for the [[Map]]'s mapKey/mapId.
   */
  def entriesRangeKeys[K](mapKey: Seq[K]): (Key.MapEntriesStart[K], Key.MapEntriesEnd[K]) =
    (Key.MapEntriesStart(mapKey), Key.MapEntriesEnd(mapKey))

  /**
   * Fetches all range key-values for all [[Map]]s within this [[Map]].
   *
   * All key-values are stored in this format. This function creates all [[Key.MapStart]] to [[Key.MapEnd]]
   * ranges for the current [[Map]] and all child [[Map]].
   *
   * MapKey.Start(Seq(1))
   *   MapKey.EntriesStart(Seq(1))
   *     MapKey.Entry(Seq(1), 1)
   *   MapKey.EntriesEnd(Seq(1))
   *   MapKey.SubMapsStart(Seq(1))
   *     MapKey.SubMap(Seq(1), 1000)
   *   MapKey.SubMapsEnd(Seq(1))
   * MapKey.End(Seq(1))
   */
  def childSubMapRanges[K, V](parentMap: Map[K, V])(implicit keySerializer: Serializer[K],
                                                    mapKeySerializer: Serializer[Key[K]],
                                                    keyOrder: KeyOrder[Slice[Byte]],
                                                    valueSerializer: Serializer[V],
                                                    optionValueSerializer: Serializer[Option[V]]): IO.ApiIO[List[(Key.SubMap[K], Key.MapStart[K], Key.MapEnd[K])]] =
    IO { //FIXME - ok a little weird with .get.
      parentMap.maps.foldLeft(List.empty[(Key.SubMap[K], Key.MapStart[K], Key.MapEnd[K])]) {
        case (previousList, (subMapKey, _)) => {
          val subMapKeys = parentMap.mapKey :+ subMapKey
          //                  remove the subMap reference from parent         &        remove subMap block
          val keysToRemove = (Key.SubMap(parentMap.mapKey, subMapKey), Key.MapStart(subMapKeys), Key.MapEnd(subMapKeys))
          previousList :+ keysToRemove
        } ++ {
          childSubMapRanges(
            Map[K, V](
              map = parentMap.baseMap(),
              mapKey = parentMap.mapKey :+ subMapKey
            )
          ).get
        }
      }.get
    }

  /**
   * Build [[Prepare.Remove]] for the input [[Key]] ranges.
   */
  def toPrepareRemove[K](prepare: Iterable[(Key.SubMap[K], Key.MapStart[K], Key.MapEnd[K])]): Iterable[Prepare.Remove[Key[K]]] =
    prepare flatMap {
      case (subMap, start, end) =>
        Seq(Prepare.Remove(subMap: Key[K]), Prepare.Remove(start: Key[K], end: Key[K]))
    }

  /**
   * Returns batch entries to create a new [[Map]].
   *
   * Note: If the map already exists, it will be removed including all it's child maps similar to a in-memory [[scala.collection.mutable.Map]].
   */
  def putMap[K, V](map: swaydb.Map[Key[K], Option[V], Nothing, IO.ApiIO],
                   mapKey: Seq[K],
                   value: Option[V])(implicit keySerializer: Serializer[K],
                                     mapKeySerializer: Serializer[Key[K]],
                                     valueSerializer: Serializer[V],
                                     optionValueSerializer: Serializer[Option[V]],
                                     keyOrder: KeyOrder[Slice[Byte]]): IO.ApiIO[Iterable[Prepare[Key[K], Option[V], Nothing]]] =
  //batch to remove all SubMaps.
    childSubMapRanges(parentMap = Map[K, V](map, mapKey)).map(toPrepareRemove) flatMap {
      removeSubMapsBatches =>
        val (thisMapEntriesStart, thisMapEntriesEnd) = Map.entriesRangeKeys(mapKey)

        //mapKey should have at least one key. A mapKey with only 1 key indicates that it's for the rootMap.
        mapKey.lastOption map {
          last =>
            IO {
              removeSubMapsBatches ++
                Seq(
                  //add subMap entry to parent Map's key
                  Prepare.Put(Key.SubMap(mapKey.dropRight(1), last), value),
                  Prepare.Remove(thisMapEntriesStart, thisMapEntriesEnd), //remove all exiting entries
                  //value only needs to be set for Start.
                  Prepare.Put(Key.MapStart(mapKey), value),
                  //values should be None for the following batch entries because they are iteration purposes only and values for
                  //entries are never read.
                  Prepare.Put(Key.MapEntriesStart(mapKey), None),
                  Prepare.Put(Key.MapEntriesEnd(mapKey), None),
                  Prepare.Put(Key.SubMapsStart(mapKey), None),
                  Prepare.Put(Key.SubMapsEnd(mapKey), None),
                  Prepare.Put(Key.MapEnd(mapKey), None)
                )
            }
        } getOrElse {
          IO.failed("Cannot put map with empty key.")
        }
    }

  def updateMapValue[K, V](mapKey: Seq[K],
                           value: V)(implicit keySerializer: Serializer[K],
                                     mapKeySerializer: Serializer[Key[K]],
                                     valueSerializer: Serializer[V],
                                     keyOrder: KeyOrder[Slice[Byte]]): Seq[Prepare.Put[Key[K], Option[V]]] =

    mapKey.lastOption map {
      last =>
        Seq[Prepare.Put[Key[K], Option[V]]](
          Prepare.Put(Key.SubMap(mapKey.dropRight(1), last), Some(value)),
          Prepare.Put(Key.MapStart(mapKey), Option(value))
        )
    } getOrElse {
      Seq(Prepare.Put(Key.MapStart(mapKey), Option(value)))
    }

  def removeMap[K, V](map: swaydb.Map[Key[K], Option[V], Nothing, IO.ApiIO],
                      mapKey: Seq[K])(implicit keySerializer: Serializer[K],
                                      mapKeySerializer: Serializer[Key[K]],
                                      valueSerializer: Serializer[V],
                                      optionValueSerializer: Serializer[Option[V]],
                                      keyOrder: KeyOrder[Slice[Byte]]): IO.ApiIO[Seq[Prepare.Remove[Key[K]]]] =
  //fetch all child subMaps from the subMap being removed and batch remove them.
    Map.childSubMapRanges(Map[K, V](map, mapKey)) map {
      childSubMapRanges =>
        List[Prepare.Remove[Key[K]]](
          Prepare.Remove(Key.SubMap[K](mapKey.dropRight(1), mapKey.last)), //remove the subMap entry from parent Map i.e this
          Prepare.Remove(Key.MapStart[K](mapKey), Key.MapEnd[K](mapKey)) //remove the subMap itself
        ) ++ Map.toPrepareRemove(childSubMapRanges)
    }
}

/**
 * Key-value or Map database API.
 *
 * For documentation check - http://swaydb.io/api/
 */
//@formatter:off
class Map[K, V](mapKey: Seq[K],
                map: swaydb.Map[Key[K], Option[V], Nothing, IO.ApiIO])(implicit keySerializer: Serializer[K],
                                                                       mapKeySerializer: Serializer[Key[K]],
                                                                       keyOrder: KeyOrder[Slice[Byte]],
                                                                       valueSerializerOption: Serializer[Option[V]],
                                                                       valueSerializer: Serializer[V]) extends MapStream[K, V](mapKey = mapKey,
                                                                                                                               map = map.copy(core = map.core,
                                                                                                                                              from = Some(From(Key.MapStart(mapKey),
                                                                                                                                              orAfter = false,
                                                                                                                                              orBefore = false,
                                                                                                                                              before = false,
                                                                                                                                              after = true)))) {
//@formatter:on

  def maps: Maps[K, V] =
    new Maps[K, V](map, mapKey)

  def exists(): IO.ApiIO[Boolean] =
    map.contains(Key.MapStart(mapKey))

  /**
   * Returns None if the map does not exist or returns the value.
   */
  def getValue(): IO.ApiIO[Option[V]] =
    map.get(Key.MapStart(mapKey)).map(_.flatten)

  def updateValue(value: V): IO.ApiIO[Map[K, V]] =
    map.commit {
      Map.updateMapValue[K, V](
        mapKey = mapKey,
        value = value
      )
    } map {
      _ =>
        Map[K, V](
          map = map,
          mapKey = mapKey
        )
    }

  def put(key: K, value: V): IO.ApiIO[Done] =
    map.put(key = Key.MapEntry(mapKey, key), value = Some(value))

  def put(key: K, value: V, expireAfter: FiniteDuration): IO.ApiIO[Done] =
    map.put(Key.MapEntry(mapKey, key), Some(value), expireAfter.fromNow)

  def put(key: K, value: V, expireAt: Deadline): IO.ApiIO[Done] =
    map.put(Key.MapEntry(mapKey, key), Some(value), expireAt)

  def put(keyValues: (K, V)*): IO.ApiIO[Done] =
    put(keyValues)

  def put(keyValues: Iterable[(K, V)]): IO.ApiIO[Done] =
    map.put {
      keyValues map {
        case (key, value) =>
          (Key.MapEntry(mapKey, key), Some(value))
      }
    }

  def preparePut(key: K, value: V): Prepare[Key.MapEntry[K], Option[V], Nothing] =
    preparePut(key, value, None)

  def preparePut(key: K, value: V, expireAfter: FiniteDuration): Prepare[Key.MapEntry[K], Option[V], Nothing] =
    preparePut(key, value, Some(expireAfter.fromNow))

  def preparePut(key: K, value: V, deadline: Deadline): Prepare[Key.MapEntry[K], Option[V], Nothing] =
    preparePut(key, value, Some(deadline))

  private def preparePut(key: K, value: V, deadline: Option[Deadline]): Prepare[Key.MapEntry[K], Option[V], Nothing] =
    Prepare.Put(Key.MapEntry(mapKey, key), value = Some(value), deadline = deadline)

  def remove(key: K): IO.ApiIO[Done] =
    map.remove(Key.MapEntry(mapKey, key))

  def remove(from: K, to: K): IO.ApiIO[Done] =
    map.remove(Key.MapEntry(mapKey, from), Key.MapEntry(mapKey, to))

  def remove(keys: K*): IO.ApiIO[Done] =
    remove(keys)

  def remove(keys: Iterable[K]): IO.ApiIO[Done] =
    map.remove(keys.map(key => Key.MapEntry(mapKey, key)))

  def prepareRemove(key: K): Prepare[Key.MapEntry[K], Option[V], Nothing] =
    makeRemoveBatch(key, None, None)

  def prepareRemove(from: K, to: K): Prepare[Key.MapEntry[K], Option[V], Nothing] =
    makeRemoveBatch(from, Some(to), None)

  def commit(entries: Prepare[Key.MapEntry[K], Option[V], Nothing]*) =
    baseMap().commit(entries)

  private def makeRemoveBatch(from: K, to: Option[K], deadline: Option[Deadline]): Prepare[Key.MapEntry[K], Option[V], Nothing] =
    Prepare.Remove(from = Key.MapEntry(mapKey, from), to = to.map(Key.MapEntry(mapKey, _)), deadline = deadline)

  /**
   * Removes all key-values from the current Map. SubMaps and subMap's key-values or not altered.
   */
  def clear(): IO.ApiIO[Done] = {
    val (start, end) = Map.entriesRangeKeys(mapKey)
    map.commit(
      //remove key-value entries, but also re-insert the start and end entries for the Map.
      Prepare.Remove(start, end),
      Prepare.Put(start, None),
      Prepare.Put(end, None)
    )
  }

  def expire(key: K, after: FiniteDuration): IO.ApiIO[Done] =
    map.expire(Key.MapEntry(mapKey, key), after.fromNow)

  def expire(key: K, at: Deadline): IO.ApiIO[Done] =
    map.expire(Key.MapEntry(mapKey, key), at)

  def expire(from: K, to: K, after: FiniteDuration): IO.ApiIO[Done] =
    map.expire(Key.MapEntry(mapKey, from), Key.MapEntry(mapKey, to), after.fromNow)

  def expire(from: K, to: K, at: Deadline): IO.ApiIO[Done] =
    map.expire(Key.MapEntry(mapKey, from), Key.MapEntry(mapKey, to), at)

  def expire(keys: (K, Deadline)*): IO.ApiIO[Done] =
    expire(keys)

  def expire(keys: Iterable[(K, Deadline)]): IO.ApiIO[Done] =
    map.expire(keys.map(keyDeadline => (Key.MapEntry(mapKey, keyDeadline._1), keyDeadline._2)))

  def update(key: K, value: V): IO.ApiIO[Done] =
    map.update(Key.MapEntry(mapKey, key), Some(value))

  def update(from: K, to: K, value: V): IO.ApiIO[Done] =
    map.update(Key.MapEntry(mapKey, from), Key.MapEntry(mapKey, to), Some(value))

  def update(keyValues: (K, V)*): IO.ApiIO[Done] =
    update(keyValues)

  def update(keyValues: Iterable[(K, V)]): IO.ApiIO[Done] =
    map.update {
      keyValues map {
        case (key, value) =>
          (Key.MapEntry(mapKey, key), Some(value))
      }
    }

  def commitPrepared(prepare: Prepare[K, V, Nothing]*): IO.ApiIO[Done] =
    this.commit(prepare)

  private def makeCommit(prepare: Prepare[K, V, Nothing]): Prepare[Key.MapEntry[K], Option[V], Nothing] =
    prepare match {
      case Prepare.Put(key, value, deadline) =>
        preparePut(key, value, deadline)

      case Prepare.Remove(from, to, deadline) =>
        Prepare.Remove(from = Key.MapEntry(mapKey, from), to = to.map(Key.MapEntry(mapKey, _)), deadline = deadline)

      case Prepare.Update(from, to, value) =>
        Prepare.Update(from = Key.MapEntry(mapKey, from), to = to.map(Key.MapEntry(mapKey, _)), value = Some(value))

      case Prepare.ApplyFunction(from, to, function) =>
        //        Prepare.ApplyFunction(Key.MapEntry(mapKey, from), to.map(Key.MapEntry(mapKey, _)), function = Key.MapEntry(Seq.empty, function))
        throw new UnsupportedOperationException("Extensions do not have functions support yet.")

      case Prepare.Add(elem, deadline) =>
        Prepare.Add(elem = Key.MapEntry(mapKey, elem), deadline = deadline)
    }

  private def makeCommit(prepare: Iterable[Prepare[K, V, Nothing]]): Iterable[Prepare[Key.MapEntry[K], Option[V], Nothing]] =
    prepare map makeCommit

  def commit(prepare: Iterable[Prepare[K, V, Nothing]]): IO.ApiIO[Done] =
    map.commit(makeCommit(prepare))

  /**
   * Returns target value for the input key.
   *
   * @return Returns None is the key does not exist.
   */
  def get(key: K): IO.ApiIO[Option[V]] =
    map.get(Key.MapEntry(mapKey, key)) flatMap {
      case Some(value) =>
        IO.Right(value)
      case None =>
        IO.none
    }

  /**
   * Returns target full key for the input partial key.
   *
   * This function is mostly used for Set databases where partial ordering on the Key is provided.
   */
  def getKey(key: K): IO.ApiIO[Option[K]] =
    map.getKey(Key.MapEntry(mapKey, key)) flatMap {
      case Some(key) =>
        key match {
          case Key.MapEntry(_, dataKey) =>
            IO.Right(Some(dataKey))
          case got =>
            IO.failed(s"Unable to fetch key. Got: $got expected MapKey.Entry")
        }
      case None =>
        IO.none
    }

  def getKeyValue(key: K): IO.ApiIO[Option[(K, V)]] =
    map.getKeyValue(Key.MapEntry(mapKey, key)) flatMap {
      case Some((key, value)) =>
        key match {
          case Key.MapEntry(_, dataKey) =>
            value map {
              value =>
                IO.Right(Some(dataKey, value))
            } getOrElse {
              IO.failed("Value does not exist.")
            }

          case got =>
            IO.failed(s"Unable to fetch keyValue. Got: $got expected MapKey.Entry")
        }
      case None =>
        IO.none
    }

  def keys: MapKeysStream[K] =
    MapKeysStream[K](
      mapKey = mapKey,
      set =
        new swaydb.Set[Key[K], Nothing, IO.ApiIO](
          core = map.core,
          from = Some(From(Key.MapStart(mapKey), orAfter = false, orBefore = false, before = false, after = true)),
          reverseIteration = isReverse
        )
    )

  def contains(key: K): IO.ApiIO[Boolean] =
    map contains Key.MapEntry(mapKey, key)

  override def size: IO.ApiIO[Int] =
    keys.size

  def mightContain(key: K): IO.ApiIO[Boolean] =
    map mightContain Key.MapEntry(mapKey, key)

  def level0Meter: LevelZeroMeter =
    map.levelZeroMeter

  def levelMeter(levelNumber: Int): Option[LevelMeter] =
    map.levelMeter(levelNumber)

  def sizeOfSegments: Long =
    map.sizeOfSegments

  def keySize(key: K): Int =
    map keySize Key.MapEntry(mapKey, key)

  def valueSize(value: V): Int =
    map valueSize Some(value)

  def expiration(key: K): IO.ApiIO[Option[Deadline]] =
    map expiration Key.MapEntry(mapKey, key)

  def timeLeft(key: K): IO.ApiIO[Option[FiniteDuration]] =
    expiration(key).map(_.map(_.timeLeft))

  def closeDatabase(): IO.ApiIO[Unit] =
    baseMap().close()

  private[swaydb] def baseMap(): swaydb.Map[Key[K], Option[V], Nothing, ApiIO] =
    map
}