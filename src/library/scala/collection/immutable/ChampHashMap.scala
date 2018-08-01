package scala
package collection.immutable

import java.io.{ObjectInputStream, ObjectOutputStream}
import java.lang.Integer.bitCount
import java.lang.System.arraycopy

import scala.annotation.unchecked.{uncheckedVariance => uV}
import scala.collection.Hashing.computeHash
import scala.collection.mutable.{Builder, ImmutableBuilder}
import scala.collection.{Iterator, MapFactory, StrictOptimizedIterableOps, StrictOptimizedMapOps}

/** This class implements immutable maps using a Compressed Hash-Array Mapped Prefix-tree.
  * See paper https://michael.steindorfer.name/publications/oopsla15.pdf for more details.
  *
  *  @tparam K      the type of the keys contained in this hash set.
  *  @tparam V      the type of the values associated with the keys in this hash map.
  *
  *  @author  Michael J. Steindorfer
  *  @since   2.13
  *  @define Coll `immutable.ChampHashMap`
  *  @define coll immutable champ hash map
  */

final class ChampHashMap[K, +V] private[immutable] (private val rootNode: MapNode[K, V], private val cachedJavaKeySetHashCode: Int, private val cachedSize: Int)
  extends AbstractMap[K, V]
    with MapOps[K, V, ChampHashMap, ChampHashMap[K, V]]
    with StrictOptimizedIterableOps[(K, V), Iterable, ChampHashMap[K, V]]
    with StrictOptimizedMapOps[K, V, ChampHashMap, ChampHashMap[K, V]] {

  override def mapFactory: MapFactory[ChampHashMap] = ChampHashMap

  override def knownSize: Int = cachedSize

  override def size: Int = cachedSize

  override def isEmpty: Boolean = cachedSize == 0

  def iterator: Iterator[(K, V)] = {
    if (isEmpty) Iterator.empty
    else new MapKeyValueTupleIterator[K, V](rootNode)
  }

  override def keysIterator: Iterator[K] = {
    if (isEmpty) Iterator.empty
    else new MapKeyIterator[K, V](rootNode)
  }
  override def valuesIterator: Iterator[V] = {
    if (isEmpty) Iterator.empty
    else new MapValueIterator[K, V](rootNode)
  }

  protected[immutable] def reverseIterator: Iterator[(K, V)] = {
    if (isEmpty) Iterator.empty
    else new MapKeyValueTupleReverseIterator[K, V](rootNode)
  }

  override final def contains(key: K): Boolean = rootNode.containsKey(key, computeHash(key), 0)

  def get(key: K): Option[V] = rootNode.get(key, computeHash(key), 0)

  override def getOrElse[V1 >: V](key: K, default: => V1): V1 = rootNode.getOrElse(key, computeHash(key), 0, default)

  def updated[V1 >: V](key: K, value: V1): ChampHashMap[K, V1] = {
    val keyHash = computeHash(key)
    val newRootNodeSource = rootNode.updated(key, value, keyHash, 0)

    if (newRootNodeSource ne rootNode) {
      newRootNodeSource match {
        case r: Replaced[K, V] =>
          ChampHashMap(newRootNodeSource.get, cachedJavaKeySetHashCode, cachedSize)
        case _ =>
          ChampHashMap(newRootNodeSource.get, cachedJavaKeySetHashCode + keyHash, cachedSize + 1)
      }
    }
    else this
  }

  def remove(key: K): ChampHashMap[K, V] = {
    val keyHash = computeHash(key)
    val newRootNode = rootNode.removed(key, keyHash, 0)

    if (newRootNode ne rootNode)
      ChampHashMap(newRootNode, cachedJavaKeySetHashCode - keyHash, cachedSize - 1)
    else this
  }

  override def concat[V1 >: V](that: scala.Iterable[(K, V1)]): ChampHashMap[K, V1] = {
    // TODO PERF We could avoid recomputing entry hash's when `that` is another `ChampHashMap`
    super.concat(that)
  }

  override def tail: ChampHashMap[K, V] = this - head._1

  override def init: ChampHashMap[K, V] = this - last._1

  override def head: (K, V) = iterator.next()

  override def last: (K, V) = reverseIterator.next()

  override def foreach[U](f: ((K, V)) => U): Unit = rootNode.foreach(f)

  override def equals(that: Any): Boolean =
    that match {
      case map: ChampHashMap[K, V] =>
        (this eq map) ||
          (this.cachedSize == map.cachedSize) &&
            (this.cachedJavaKeySetHashCode == map.cachedJavaKeySetHashCode) &&
            (this.rootNode == map.rootNode)
      case _ => super.equals(that)
    }


  override protected[this] def className = "ChampHashMap"
}

private[immutable] object MapNode {

  private final val EmptyMapNode = new BitmapIndexedMapNode(0, 0, Array.empty)

  def empty[K, V]: MapNode[K, V] =
    EmptyMapNode.asInstanceOf[MapNode[K, V]]

  final val TupleLength = 2

}

private[immutable] abstract class MapNodeSource[K, +V] {
  def get: MapNode[K, V]
}

private final class Replaced[K, V](node: MapNode[K, V @uV]) extends MapNodeSource[K, V]{
  var value = node
  def get = value
}

private[immutable] sealed abstract class MapNode[K, +V] extends MapNodeSource[K, V] with Node[MapNode[K, V @uV]] {
  final def get: MapNode[K, V] = this

  def get(key: K, hash: Int, shift: Int): Option[V]
  def getOrElse[V1 >: V](key: K, hash: Int, shift: Int, f: => V1): V1

  def containsKey(key: K, hash: Int, shift: Int): Boolean

  def updated[V1 >: V](key: K, value: V1, hash: Int, shift: Int): MapNodeSource[K, V1]

  def removed[V1 >: V](key: K, hash: Int, shift: Int): MapNode[K, V1]

  def hasNodes: Boolean

  def nodeArity: Int

  def getNode(index: Int): MapNode[K, V]

  def hasPayload: Boolean

  def payloadArity: Int

  def getKey(index: Int): K

  def getValue(index: Int): V

  def getPayload(index: Int): (K, V)

  def sizePredicate: Int

  def foreach[U](f: ((K, V)) => U): Unit

}

private class BitmapIndexedMapNode[K, +V](val dataMap: Int, val nodeMap: Int, val content: Array[Any]) extends MapNode[K, V] {

  import MapNode._
  import Node._

  /*
  assert(checkInvariantContentIsWellTyped())
  assert(checkInvariantSubNodesAreCompacted())

  private final def checkInvariantSubNodesAreCompacted(): Boolean =
    new MapKeyValueTupleIterator[K, V](this).size - payloadArity >= 2 * nodeArity

  private final def checkInvariantContentIsWellTyped(): Boolean = {
    val predicate1 = TupleLength * payloadArity + nodeArity == content.length

    val predicate2 = Range(0, TupleLength * payloadArity)
      .forall(i => content(i).isInstanceOf[MapNode[_, _]] == false)

    val predicate3 = Range(TupleLength * payloadArity, content.length)
      .forall(i => content(i).isInstanceOf[MapNode[_, _]] == true)

    predicate1 && predicate2 && predicate3
  }
  */

  def getKey(index: Int): K = content(TupleLength * index).asInstanceOf[K]
  def getValue(index: Int): V = content(TupleLength * index + 1).asInstanceOf[V]

  def getPayload(index: Int) = Tuple2(
    content(TupleLength * index).asInstanceOf[K],
    content(TupleLength * index + 1).asInstanceOf[V])

  def getNode(index: Int) =
    content(content.length - 1 - index).asInstanceOf[MapNode[K, V]]

  def get(key: K, keyHash: Int, shift: Int): Option[V] = {
    val mask = maskFrom(keyHash, shift)
    val bitpos = bitposFrom(mask)

    if ((dataMap & bitpos) != 0) {
      val index = indexFrom(dataMap, mask, bitpos)
      val key0 = this.getKey(index)
      return if (key == key0) Some(this.getValue(index)) else None
    }

    if ((nodeMap & bitpos) != 0) {
      val index = indexFrom(nodeMap, mask, bitpos)
      return this.getNode(index).get(key, keyHash, shift + BitPartitionSize)
    }

    None
  }
  def getOrElse[V1 >: V](key: K, keyHash: Int, shift: Int, f: => V1): V1 = {
    val mask = maskFrom(keyHash, shift)
    val bitpos = bitposFrom(mask)

    if ((dataMap & bitpos) != 0) {
      val index = indexFrom(dataMap, mask, bitpos)
      val key0 = this.getKey(index)
      return if (key == key0) this.getValue(index) else f
    }

    if ((nodeMap & bitpos) != 0) {
      val index = indexFrom(nodeMap, mask, bitpos)
      return this.getNode(index).getOrElse(key, keyHash, shift + BitPartitionSize, f)
    }
    f
  }

  override def containsKey(key: K, keyHash: Int, shift: Int): Boolean = {
    val mask = maskFrom(keyHash, shift)
    val bitpos = bitposFrom(mask)

    if ((dataMap & bitpos) != 0) {
      val index = indexFrom(dataMap, mask, bitpos)
      return key == this.getKey(index)
    }

    if ((nodeMap & bitpos) != 0) {
      val index = indexFrom(nodeMap, mask, bitpos)
      return this.getNode(index).containsKey(key, keyHash, shift + BitPartitionSize)
    }

    false
  }

  def updated[V1 >: V](key: K, value: V1, keyHash: Int, shift: Int): MapNodeSource[K, V1] = {
    val mask = maskFrom(keyHash, shift)
    val bitpos = bitposFrom(mask)

    if ((dataMap & bitpos) != 0) {
      val index = indexFrom(dataMap, mask, bitpos)
      val key0 = this.getKey(index)
      if (key0 == key) {
        val value0 = this.getValue(index)
        return (
          if (value0.asInstanceOf[AnyRef] eq value.asInstanceOf[AnyRef])
            // TODO should we also check that `key0 eq key` in addition to being ==
            this
          else copyAndSetValue(bitpos, value)
        )
      } else {
        val value0 = this.getValue(index)
        val subNodeNew = mergeTwoKeyValPairs(key0, value0, computeHash(key0), key, value, keyHash, shift + BitPartitionSize)
        return copyAndMigrateFromInlineToNode(bitpos, subNodeNew)
      }
    }

    if ((nodeMap & bitpos) != 0) {
      val index = indexFrom(nodeMap, mask, bitpos)
      val subNodeSource = this.getNode(index)

      val subNodeNew = subNodeSource.updated(key, value, keyHash, shift + BitPartitionSize)
      if (subNodeNew eq subNodeSource) {
        return this
      } else {
        return copyAndSetNode(bitpos, subNodeNew)
      }
    }

    copyAndInsertValue(bitpos, key, value)
  }

  def removed[V1 >: V](key: K, keyHash: Int, shift: Int): MapNode[K, V1] = {
    val mask = maskFrom(keyHash, shift)
    val bitpos = bitposFrom(mask)

    if ((dataMap & bitpos) != 0) {
      val index = indexFrom(dataMap, mask, bitpos)
      val key0 = this.getKey(index)

      if (key0 == key) {
        if (this.payloadArity == 2 && this.nodeArity == 0) {
          /*
           * Create new node with remaining pair. The new node will a) either become the new root
           * returned, or b) unwrapped and inlined during returning.
           */
          val newDataMap = if (shift == 0) (dataMap ^ bitpos) else bitposFrom(maskFrom(keyHash, 0))
          if (index == 0)
            return new BitmapIndexedMapNode[K, V1](newDataMap, 0, Array(getKey(1), getValue(1)))
          else
            return new BitmapIndexedMapNode[K, V1](newDataMap, 0, Array(getKey(0), getValue(0)))
        }
        else return copyAndRemoveValue(bitpos)
      } else return this
    }

    if ((nodeMap & bitpos) != 0) {
      val index = indexFrom(nodeMap, mask, bitpos)
      val subNode = this.getNode(index)

      val subNodeNew = subNode.removed(key, keyHash, shift + BitPartitionSize)
      // assert(subNodeNew.sizePredicate != SizeEmpty, "Sub-node must have at least one element.")

      if (subNodeNew eq subNode) return this
      subNodeNew.sizePredicate match {
        case SizeOne =>
          if (this.payloadArity == 0 && this.nodeArity == 1) { // escalate (singleton or empty) result
            return subNodeNew
          }
          else { // inline value (move to front)
            return copyAndMigrateFromNodeToInline(bitpos, subNodeNew)
          }

        case SizeMoreThanOne =>
          // modify current node (set replacement node)
          val newNodeSource = copyAndSetNode(bitpos, subNodeNew)
          assert(!newNodeSource.isInstanceOf[Replaced[_, _]])
          return newNodeSource.get
      }
    }

    this
  }

  def mergeTwoKeyValPairs[V1 >: V](key0: K, value0: V1, keyHash0: Int, key1: K, value1: V1, keyHash1: Int, shift: Int): MapNode[K, V1] = {
    // assert(key0 != key1)

    if (shift >= HashCodeLength) {
      new HashCollisionMapNode[K, V1](keyHash0, Vector((key0, value0), (key1, value1)))
    } else {
      val mask0 = maskFrom(keyHash0, shift)
      val mask1 = maskFrom(keyHash1, shift)

      if (mask0 != mask1) {
        // unique prefixes, payload fits on same level
        val dataMap = bitposFrom(mask0) | bitposFrom(mask1)

        if (mask0 < mask1) {
          new BitmapIndexedMapNode[K, V1](dataMap, 0, Array(key0, value0, key1, value1))
        } else {
          new BitmapIndexedMapNode[K, V1](dataMap, 0, Array(key1, value1, key0, value0))
        }
      } else {
        // identical prefixes, payload must be disambiguated deeper in the trie
        val nodeMap = bitposFrom(mask0)
        val node = mergeTwoKeyValPairs(key0, value0, keyHash0, key1, value1, keyHash1, shift + BitPartitionSize)

        new BitmapIndexedMapNode[K, V1](0, nodeMap, Array(node))
      }
    }
  }
  
  def sizePredicate: Int =
    if (nodeArity == 0) payloadArity match {
      case 0 => SizeEmpty
      case 1 => SizeOne
      case _ => SizeMoreThanOne
    } else SizeMoreThanOne

  def hasNodes: Boolean = nodeMap != 0

  def nodeArity: Int = bitCount(nodeMap)

  def hasPayload: Boolean = dataMap != 0

  def payloadArity: Int = bitCount(dataMap)

  def dataIndex(bitpos: Int) = bitCount(dataMap & (bitpos - 1))

  def nodeIndex(bitpos: Int) = bitCount(nodeMap & (bitpos - 1))

  def copyAndSetValue[V1 >: V](bitpos: Int, newValue: V1): Replaced[K, V1] = {
    val idx = TupleLength * dataIndex(bitpos) + 1

    val src = this.content
    val dst = new Array[Any](src.length)

    // copy 'src' and set 1 element(s) at position 'idx'
    arraycopy(src, 0, dst, 0, src.length)
    dst(idx) = newValue
    new Replaced(new BitmapIndexedMapNode[K, V1](dataMap, nodeMap, dst))
  }

  def copyAndSetNode[V1 >: V](bitpos: Int, newNode: MapNodeSource[K, V1]): MapNodeSource[K, V1] = {
    val idx = this.content.length - 1 - this.nodeIndex(bitpos)

    val src = this.content
    val dst = new Array[Any](src.length)

    // copy 'src' and set 1 element(s) at position 'idx'
    arraycopy(src, 0, dst, 0, src.length)
    newNode match {
      case r: Replaced[K, V1] =>
        dst(idx) = r.get
        r.value = new BitmapIndexedMapNode[K, V1](dataMap, nodeMap, dst)
        r
      case mn: MapNode[K, V1] =>
        dst(idx) = mn.get
        new BitmapIndexedMapNode[K, V1](dataMap, nodeMap, dst)
    }
  }

  def copyAndInsertValue[V1 >: V](bitpos: Int, key: K, value: V1): BitmapIndexedMapNode[K, V1] = {
    val idx = TupleLength * dataIndex(bitpos)

    val src = this.content
    val dst = new Array[Any](src.length + TupleLength)

    // copy 'src' and insert 2 element(s) at position 'idx'
    arraycopy(src, 0, dst, 0, idx)
    dst(idx) = key
    dst(idx + 1) = value
    arraycopy(src, idx, dst, idx + TupleLength, src.length - idx)

    new BitmapIndexedMapNode[K, V1](dataMap | bitpos, nodeMap, dst)
  }

  def copyAndRemoveValue(bitpos: Int): BitmapIndexedMapNode[K, V] = {
    val idx = TupleLength * dataIndex(bitpos)

    val src = this.content
    val dst = new Array[Any](src.length - TupleLength)

    // copy 'src' and remove 2 element(s) at position 'idx'
    arraycopy(src, 0, dst, 0, idx)
    arraycopy(src, idx + TupleLength, dst, idx, src.length - idx - TupleLength)

    new BitmapIndexedMapNode[K, V](dataMap ^ bitpos, nodeMap, dst)
  }

  def copyAndMigrateFromInlineToNode[V1 >: V](bitpos: Int, node: MapNode[K, V1]): BitmapIndexedMapNode[K, V1] = {
    val idxOld = TupleLength * dataIndex(bitpos)
    val idxNew = this.content.length - TupleLength - nodeIndex(bitpos)

    val src = this.content
    val dst = new Array[Any](src.length - TupleLength + 1)

    // copy 'src' and remove 2 element(s) at position 'idxOld' and
    // insert 1 element(s) at position 'idxNew'
    // assert(idxOld <= idxNew)
    arraycopy(src, 0, dst, 0, idxOld)
    arraycopy(src, idxOld + TupleLength, dst, idxOld, idxNew - idxOld)
    dst(idxNew) = node
    arraycopy(src, idxNew + TupleLength, dst, idxNew + 1, src.length - idxNew - TupleLength)

    new BitmapIndexedMapNode[K, V1](dataMap ^ bitpos, nodeMap | bitpos, dst)
  }

  def copyAndMigrateFromNodeToInline[V1 >: V](bitpos: Int, node: MapNode[K, V1]): BitmapIndexedMapNode[K, V1] = {
    val idxOld = this.content.length - 1 - nodeIndex(bitpos)
    val idxNew = TupleLength * dataIndex(bitpos)

    val key = node.getKey(0)
    val value = node.getValue(0)
    val src = this.content
    val dst = new Array[Any](src.length - 1 + TupleLength)

    // copy 'src' and remove 1 element(s) at position 'idxOld' and
    // insert 2 element(s) at position 'idxNew'
    // assert(idxOld >= idxNew)
    arraycopy(src, 0, dst, 0, idxNew)
    dst(idxNew) = key
    dst(idxNew + 1) = value
    arraycopy(src, idxNew, dst, idxNew + TupleLength, idxOld - idxNew)
    arraycopy(src, idxOld + 1, dst, idxOld + TupleLength, src.length - idxOld - 1)

    new BitmapIndexedMapNode[K, V1](dataMap | bitpos, nodeMap ^ bitpos, dst)
  }

  override def foreach[U](f: ((K, V)) => U): Unit = {
    var i = 0
    while (i < payloadArity) {
      f(getPayload(i))
      i += 1
    }

    var j = 0
    while (j < nodeArity) {
      getNode(j).foreach(f)
      j += 1
    }
  }

  override def equals(that: Any): Boolean =
    that match {
      case node: BitmapIndexedMapNode[K, V] =>
        (this eq node) ||
          (this.nodeMap == node.nodeMap) &&
            (this.dataMap == node.dataMap) &&
            deepContentEquality(this.content, node.content, content.length)
      case _ => false
    }

  @`inline` private def deepContentEquality(a1: Array[Any], a2: Array[Any], length: Int): Boolean = {
    if (a1 eq a2)
      true
    else {
      var isEqual = true
      var i = 0

      while (isEqual && i < length) {
        isEqual = a1(i) == a2(i)
        i += 1
      }

      isEqual
    }
  }

  override def hashCode(): Int =
    throw new UnsupportedOperationException("Trie nodes do not support hashing.")

}

private class HashCollisionMapNode[K, +V](val hash: Int, val content: Vector[(K, V)]) extends MapNode[K, V] {

  import Node._

  require(content.size >= 2)

  def get(key: K, hash: Int, shift: Int): Option[V] =
    if (this.hash == hash) content.find(key == _._1).map(_._2) else None
  def getOrElse[V1 >: V](key: K, hash: Int, shift: Int, f: => V1): V1 = {
    if (this.hash == hash) {
      content.find(key == _._1) match {
        case Some(pair) => pair._2
        case None => f
      }
    } else f
  }

  override def containsKey(key: K, hash: Int, shift: Int): Boolean =
    this.hash == hash && content.exists(key == _._1)

  def contains[V1 >: V](key: K, value: V1, hash: Int, shift: Int): Boolean =
    this.hash == hash && content.exists(payload => key == payload._1 && (value.asInstanceOf[AnyRef] eq payload._2.asInstanceOf[AnyRef]))

  def updated[V1 >: V](key: K, value: V1, hash: Int, shift: Int): MapNodeSource[K, V1] =
    if (this.contains(key, value, hash, shift)) {
      this
    } else if (this.containsKey(key, hash, shift)) {
      val index = content.indexWhere(key == _._1)
      val (beforeTuple, fromTuple) = content.splitAt(index)
      val updatedContent = beforeTuple.appended(Tuple2(key, value)).appendedAll(fromTuple.drop(1))
      new Replaced(new HashCollisionMapNode[K, V1](hash, updatedContent))
    } else {
      new HashCollisionMapNode[K, V1](hash, content.appended(Tuple2(key, value)))
    }

  def removed[V1 >: V](key: K, hash: Int, shift: Int): MapNode[K, V1] =
    if (!this.containsKey(key, hash, shift)) {
      this
    } else {
      val updatedContent = content.filterNot(keyValuePair => keyValuePair._1 == key)
      // assert(updatedContent.size == content.size - 1)

      updatedContent.size match {
        case 1 => new BitmapIndexedMapNode[K, V1](bitposFrom(maskFrom(hash, 0)), 0, { val (k, v) = updatedContent(0) ; Array(k, v) })
        case _ => new HashCollisionMapNode[K, V1](hash, updatedContent)
      }
    }

  def hasNodes: Boolean = false

  def nodeArity: Int = 0

  def getNode(index: Int): MapNode[K, V] =
    throw new IndexOutOfBoundsException("No sub-nodes present in hash-collision leaf node.")

  def hasPayload: Boolean = true

  def payloadArity: Int = content.size

  def getKey(index: Int): K = getPayload(index)._1
  def getValue(index: Int): V = getPayload(index)._2

  def getPayload(index: Int): (K, V) = content(index)

  def sizePredicate: Int = SizeMoreThanOne

  def foreach[U](f: ((K, V)) => U): Unit = content.foreach(f)

  override def equals(that: Any): Boolean =
    that match {
      case node: HashCollisionMapNode[K, V] =>
        (this eq node) ||
          (this.hash == node.hash) &&
            (this.content.size == node.content.size) &&
            (this.content.forall(node.content.contains))
      case _ => false
    }

  override def hashCode(): Int =
    throw new UnsupportedOperationException("Trie nodes do not support hashing.")

}

private final class MapKeyIterator[K, V](rootNode: MapNode[K, V])
  extends ChampBaseIterator[MapNode[K, V]](rootNode) with Iterator[K] {

  def next() = {
    if (!hasNext)
      throw new NoSuchElementException

    val key = currentValueNode.getKey(currentValueCursor)
    currentValueCursor += 1

    key
  }

}

private final class MapValueIterator[K, V](rootNode: MapNode[K, V])
  extends ChampBaseIterator[MapNode[K, V]](rootNode) with Iterator[V] {

  def next() = {
    if (!hasNext)
      throw new NoSuchElementException

    val value = currentValueNode.getValue(currentValueCursor)
    currentValueCursor += 1

    value
  }
}

private final class MapKeyValueTupleIterator[K, V](rootNode: MapNode[K, V])
  extends ChampBaseIterator[MapNode[K, V]](rootNode) with Iterator[(K, V)] {

  def next() = {
    if (!hasNext)
      throw new NoSuchElementException

    val payload = currentValueNode.getPayload(currentValueCursor)
    currentValueCursor += 1

    payload
  }

}

private final class MapKeyValueTupleReverseIterator[K, V](rootNode: MapNode[K, V])
  extends ChampBaseReverseIterator[MapNode[K, V]](rootNode) with Iterator[(K, V)] {

  def next() = {
    if (!hasNext)
      throw new NoSuchElementException

    val payload = currentValueNode.getPayload(currentValueCursor)
    currentValueCursor -= 1

    payload
  }

}

/**
  * $factoryInfo
  *
  * @define Coll `immutable.ChampHashMap`
  * @define coll immutable champ hash map
  */
@SerialVersionUID(3L)
object ChampHashMap extends MapFactory[ChampHashMap] {

  private[ChampHashMap] def apply[K, V](rootNode: MapNode[K, V], cachedJavaHashCode: Int, cachedSize: Int) =
    new ChampHashMap[K, V](rootNode, cachedJavaHashCode, cachedSize)

  private final val EmptyMap = new ChampHashMap(MapNode.empty, 0, 0)

  def empty[K, V]: ChampHashMap[K, V] =
    EmptyMap.asInstanceOf[ChampHashMap[K, V]]

  def from[K, V](source: collection.IterableOnce[(K, V)]): ChampHashMap[K, V] =
    source match {
      case hs: ChampHashMap[K, V] => hs
      case _ => (newBuilder[K, V] ++= source).result()
    }

  def newBuilder[K, V]: Builder[(K, V), ChampHashMap[K, V]] =
    new ImmutableBuilder[(K, V), ChampHashMap[K, V]](empty) {
      def addOne(element: (K, V)): this.type = {
        elems = elems + element
        this
      }
    }

  // scalac generates a `readReplace` method to discard the deserialized state (see https://github.com/scala/bug/issues/10412).
  // This prevents it from serializing it in the first place:
  private[this] def writeObject(out: ObjectOutputStream): Unit = ()
  private[this] def readObject(in: ObjectInputStream): Unit = ()
}
