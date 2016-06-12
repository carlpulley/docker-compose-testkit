package cakesolutions.docker.testkit.filters

import rx.lang.scala.Observable

object ObservableFilter {
  implicit class ObservableMatchFilter[T](obs: Observable[T]) {
    def matchFirst(eventMatch: T => Boolean): Observable[(Int, T)] = {
      obs
        .map { entry =>
          if (eventMatch(entry)) {
            Some(entry)
          } else {
            None
          }
        }
        .collect { case Some(entry) => (0, entry) }
        .first
    }

    def matchFirstOrdered(eventMatches: (T => Boolean)*): Observable[(Int, T)] = {
      obs
        .scan[(Option[(Int, T)], Int)]((None, 0)) {
        case ((_, matchedEventsCount), entry) if matchedEventsCount < eventMatches.length && eventMatches(matchedEventsCount)(entry) =>
          (Some((matchedEventsCount, entry)), matchedEventsCount + 1)
        case ((_, matchedEventsCount), _) =>
          (None, matchedEventsCount)
      }
        .collect { case (Some(indexedEntry), _) => indexedEntry }
    }

    def matchFirstUnordered(eventMatches: (T => Boolean)*): Observable[(Int, T)] = {
      obs
        .scan[(Option[(Int, T)], Set[Int])]((None, Set.empty)) {
        case ((_, matchedEventIndexes), entry) =>
          val index = eventMatches.indexWhere(_(entry))
          if (index >= 0 && ! matchedEventIndexes.contains(index)) {
            (Some((index, entry)), matchedEventIndexes + index)
          } else {
            (None, matchedEventIndexes)
          }
      }
        .collect { case (Some(indexedEntry), _) => indexedEntry }
    }

    def matchUnordered(eventMatches: (T => Boolean)*): Observable[(Int, T)] = {
      obs
        .scan[(Option[(Int, T)], Set[Int])]((None, Set.empty)) {
        case ((_, matchedEventIndexes), entry) =>
          val index = eventMatches.indexWhere(_(entry))
          if (index >= 0) {
            (Some((index, entry)), matchedEventIndexes + index)
          } else {
            (None, matchedEventIndexes)
          }
      }
        .collect { case (Some(indexedEntry), _) => indexedEntry }
    }
  }
}
