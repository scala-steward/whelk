package org.geneontology.whelk

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import BuiltIn._

import scalaz._
import scalaz.Scalaz._

final case class Reasoner(
  todo:                   Queue[QueueExpression], // need to initialize from ont
  concIncs:               Set[ConceptInclusion], // based on ont
  concIncsBySubclass:     Map[Concept, List[ConceptInclusion]],
  inits:                  Set[Concept], // closure
  subs:                   Set[ConceptInclusion], // closure
  subsBySubclass:         Map[Concept, Set[Concept]],
  links:                  Set[Link], // closure
  linksBySubject:         Map[Concept, List[Link]],
  linksByTarget:          Map[Concept, List[Link]],
  negConjs:               Set[Conjunction], // based on ont
  negConjsByOperand:      Map[Concept, Map[Concept, Conjunction]], // based on ont
  negConjsByOperandLeft:  Map[Concept, Map[Concept, Conjunction]], // based on ont
  negConjsByOperandRight: Map[Concept, Map[Concept, Conjunction]], // based on ont
  negExistsMap:           Map[Role, Map[Concept, ExistentialRestriction]],
  negExistsMapByConcept:  Map[Concept, Map[Role, ExistentialRestriction]],
  negExists:              Set[ExistentialRestriction], // based on ont
  propagations:           Map[Concept, Map[Role, Set[ExistentialRestriction]]],
  hier:                   Map[Role, Set[Role]], // based on ont
  roleComps:              Map[(Role, Role), Set[Role]], // based on ont
  hierComps:              Map[Role, Map[Role, Set[Role]]],
  topOccursNegatively:    Boolean) // based on ont

object Reasoner {

  val empty: Reasoner = Reasoner(Queue.empty, Set.empty, Map.empty, Set.empty, Set.empty, Map.empty, Set.empty, Map.empty, Map.empty, Set.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Set.empty, Map.empty, Map.empty, Map.empty, Map.empty, false)

  def index(axioms: Set[Axiom], reasoner: Reasoner): Reasoner = {
    import scalaz.syntax.semigroup._
    val concIncs = reasoner.concIncs |+| axioms.collect { case ci: ConceptInclusion => ci }
    val concIncsBySubclass = reasoner.concIncsBySubclass |+| concIncs.groupBy(_.subclass).map { case (concept, cis) => concept -> cis.toList }
    val todo = reasoner.todo.enqueue(concIncs)
    /////////
    val allRoles = axioms.flatMap(_.signature).collect { case role: Role => role }
    val allRoleInclusions = axioms.collect { case ri: RoleInclusion => ri }
    val hier = saturateRoles(allRoleInclusions) |+| allRoles.map(r => r -> Set(r)).toMap
    val roleComps = axioms.collect { case rc: RoleComposition => rc }.groupBy(rc => (rc.first, rc.second)).map {
      case (key, ris) =>
        key -> ris.map(_.superproperty)
    }
    val hierCompsTuples = (for {
      (r1, s1s) <- hier
      s1 <- s1s
      (r2, s2s) <- hier
      s2 <- s2s
      s <- roleComps.getOrElse((s1, s2), Set.empty)
    } yield (r1, r2, s)).toSet
    val hierCompsRemove = for {
      (r1, r2, s) <- hierCompsTuples
      s0 <- hier(s)
      if s0 != s
      if hierCompsTuples((r1, r2, s0))
    } yield (r1, r2, s)
    val hierComps = (hierCompsTuples -- hierCompsRemove).groupBy(_._1).map {
      case (r1, values) => r1 -> (values.map {
        case (r1, r2, s) => (r2, s)
      }).groupBy(_._1).map {
        case (r2, ss) => r2 -> ss.map(_._2)
      }
    }
    ////////
    val negativeConcepts = concIncs.flatMap(_.subclass.conceptSignature)
    val negConjs = reasoner.negConjs |+| negativeConcepts.collect { case conj: Conjunction => conj }
    val negConjsByOperand = negConjs.groupBy(_.left).map { case (concept, m) => concept -> m.map(conj => conj.right -> conj).toMap }
    val negConjsByOperandLeft = negConjs.groupBy(_.left).map { case (concept, m) => concept -> m.map(conj => conj.right -> conj).toMap }
    val negConjsByOperandRight = negConjs.groupBy(_.right).map { case (concept, m) => concept -> m.map(conj => conj.left -> conj).toMap }
    val negExists = reasoner.negExists |+| negativeConcepts.collect { case er: ExistentialRestriction => er }
    val negExistsMap = negExists.groupBy(_.role).map {
      case (role, ers) => role -> ers.map(er => er.concept -> er).toMap
    }
    val negExistsMapByConcept = negExists.groupBy(_.concept).map {
      case (concept, ers) => concept -> ers.map(er => er.role -> er).toMap
    }
    reasoner.copy(todo = todo, concIncs = concIncs, concIncsBySubclass = concIncsBySubclass, hier = hier, roleComps = roleComps, hierComps = hierComps, negConjs = negConjs, negConjsByOperand = negConjsByOperand, negExists = negExists, negExistsMap = negExistsMap, negExistsMapByConcept = negExistsMapByConcept, topOccursNegatively = negativeConcepts(Top))
  }

  @tailrec
  def computeClosure(reasoner: Reasoner): Reasoner = if (reasoner.todo.nonEmpty) {
    val (item, todo) = reasoner.todo.dequeue
    computeClosure(process(item, reasoner.copy(todo = todo)))
  } else reasoner

  private def process(expression: QueueExpression, reasoner: Reasoner): Reasoner = expression match {
    case concept: Concept => if (reasoner.inits(concept)) reasoner else
      `R⊤`(concept, R0(concept, reasoner.copy(inits = reasoner.inits + concept)))
    case ci @ ConceptInclusion(subclass, superclass) => if (reasoner.subs(ci)) reasoner else {
      val subs = reasoner.subs + ci
      val subsBySubclass = reasoner.subsBySubclass + (ci.subclass -> (reasoner.subsBySubclass.getOrElse(ci.subclass, Set.empty) + ci.superclass))
      import scalaz.syntax.semigroup._
      val propagations: Map[Concept, Map[Role, Set[ExistentialRestriction]]] = reasoner.propagations |+| Map(ci.subclass -> reasoner.negExistsMapByConcept.getOrElse(ci.superclass, Map.empty).map { case (role, er) => role -> Set(er) })
      //`R⊑`(ci, `R+∃`(ci, `R-∃`(ci, `R+⨅`(ci, `R-⨅`(ci, `R⊥`(ci, reasoner.copy(subs = subs, subsBySubclass = subsBySubclass, propagations = propagations)))))))
      `R⊑`(ci, `R+∃old`(ci, `R-∃`(ci, `R+⨅`(ci, `R-⨅`(ci, `R⊥`(ci, reasoner.copy(subs = subs, subsBySubclass = subsBySubclass, propagations = propagations)))))))
    }
    case `Sub+`(ci @ ConceptInclusion(subclass, superclass)) => if (reasoner.subs(ci)) reasoner else {
      val subs = reasoner.subs + ci
      val subsBySubclass = reasoner.subsBySubclass + (ci.subclass -> (reasoner.subsBySubclass.getOrElse(ci.subclass, Set.empty) + ci.superclass))
      import scalaz.syntax.semigroup._
      val propagations: Map[Concept, Map[Role, Set[ExistentialRestriction]]] = reasoner.propagations |+| Map(ci.subclass -> reasoner.negExistsMapByConcept.getOrElse(ci.superclass, Map.empty).map { case (role, er) => role -> Set(er) })
      //`R⊑`(ci, `R+∃`(ci, `R-∃`(ci, `R+⨅`(ci, `R-⨅`(ci, `R⊥`(ci, reasoner.copy(subs = subs, subsBySubclass = subsBySubclass, propagations = propagations)))))))
      `R⊑`(ci, `R+∃old`(ci, `R+⨅`(ci, `R⊥`(ci, reasoner.copy(subs = subs, subsBySubclass = subsBySubclass, propagations = propagations)))))
    }
    case link @ Link(subclass, role, superclass) => if (reasoner.links(link)) reasoner else {
      val links = reasoner.links + link
      val linksBySubject = reasoner.linksBySubject + (link.subject -> (link :: reasoner.linksBySubject.getOrElse(link.subject, Nil)))
      val linksByTarget = reasoner.linksByTarget + (link.target -> (link :: reasoner.linksByTarget.getOrElse(link.target, Nil)))
      `R⤳`(link, `R∘`(link, `R+∃`(link, `R⊥`(link, reasoner.copy(links = links, linksBySubject = linksBySubject, linksByTarget = linksByTarget)))))
      //`R⤳`(link, `R∘`(link, `R+∃old`(link, `R⊥`(link, reasoner.copy(links = links, linksBySubject = linksBySubject, linksByTarget = linksByTarget)))))
    }
  }

  private def R0(concept: Concept, reasoner: Reasoner): Reasoner =
    reasoner.copy(todo = reasoner.todo.enqueue(ConceptInclusion(concept, concept)))

  private def `R⊤`(concept: Concept, reasoner: Reasoner): Reasoner =
    if (reasoner.topOccursNegatively) reasoner.copy(todo = reasoner.todo.enqueue(ConceptInclusion(concept, Top)))
    else reasoner

  private def `R⊥`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = {
    var todo = reasoner.todo
    if (ci.superclass == Bottom) {
      reasoner.linksByTarget.getOrElse(ci.subclass, Nil).foreach { link =>
        todo = todo.enqueue(ConceptInclusion(link.subject, Bottom))
      }
      reasoner.copy(todo = todo)
    } else reasoner
  }

  private def `R-⨅`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = ci match {
    case ConceptInclusion(sub, Conjunction(left, right)) => reasoner.copy(todo = reasoner.todo
      .enqueue(ConceptInclusion(sub, left))
      .enqueue(ConceptInclusion(sub, right)))
    case _ => reasoner
  }

  private def `R+⨅`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = {
    var todo = reasoner.todo
    val superclasses = reasoner.subsBySubclass(ci.subclass)
    reasoner.negConjsByOperandLeft.getOrElse(ci.superclass, Map.empty).foreach {
      case (right, conj) =>
        if (superclasses(right)) {
          todo = todo.enqueue(`Sub+`(ConceptInclusion(ci.subclass, conj)))
        }
    }
    reasoner.negConjsByOperandRight.getOrElse(ci.superclass, Map.empty).foreach {
      case (left, conj) =>
        if (superclasses(left)) {
          todo = todo.enqueue(`Sub+`(ConceptInclusion(ci.subclass, conj)))
        }
    }
    reasoner.copy(todo = todo)
  }

  // Different join order - much slower on Uberon
  private def `R+⨅subsFirst`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = {
    var todo = reasoner.todo
    reasoner.subsBySubclass.getOrElse(ci.subclass, Set.empty).foreach { superclass =>
      reasoner.negConjsByOperand.getOrElse(ci.superclass, Map.empty).get(superclass).foreach { conj =>
        todo = todo.enqueue(`Sub+`(ConceptInclusion(ci.subclass, conj)))
      }
      reasoner.negConjsByOperand.getOrElse(superclass, Map.empty).get(ci.superclass).foreach { conj =>
        todo = todo.enqueue(`Sub+`(ConceptInclusion(ci.subclass, conj)))
      }
    }
    reasoner.copy(todo = todo)
  }

  private def `R-∃`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = ci match {
    case ConceptInclusion(c, ExistentialRestriction(role, filler)) => reasoner.copy(todo = reasoner.todo
      .enqueue(Link(c, role, filler)))
    case _ => reasoner
  }

  private def `R+∃old`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = { //*
    var todo = reasoner.todo
    for {
      Link(e, r, _) <- reasoner.linksByTarget.getOrElse(ci.subclass, Nil)
      s <- reasoner.hier.getOrElse(r, Set.empty)
      ers <- reasoner.negExistsMap.get(s)
      f <- ers.get(ci.superclass)
    } todo = todo.enqueue(`Sub+`(ConceptInclusion(e, f)))
    reasoner.copy(todo = todo)
  }

  private def `R⊑`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = {
    var todo = reasoner.todo
    reasoner.concIncsBySubclass.getOrElse(ci.superclass, Nil).foreach { other =>
      todo = todo.enqueue(ConceptInclusion(ci.subclass, other.superclass))
    }
    reasoner.copy(todo = todo)
  }

  private def `R⊥`(link: Link, reasoner: Reasoner): Reasoner = {
    if (reasoner.subs(ConceptInclusion(link.target, Bottom)))
      reasoner.copy(todo = reasoner.todo.enqueue(ConceptInclusion(link.subject, Bottom)))
    else reasoner
  }

  private def `R+∃`(link: Link, reasoner: Reasoner): Reasoner = {
    // link: E R C
    // props[R, C, F]; F= RsomeD
    var todo = reasoner.todo
    for {
      roleToER <- reasoner.propagations.get(link.target).toSeq
      s <- reasoner.hier.getOrElse(link.role, Set.empty)
      fs <- roleToER.get(s)
      f <- fs
    } todo = todo.enqueue(`Sub+`(ConceptInclusion(link.subject, f)))
    reasoner.copy(todo = todo)
  }

  private def `R+∃old`(link: Link, reasoner: Reasoner): Reasoner = {
    var todo = reasoner.todo
    for {
      d <- reasoner.subsBySubclass.getOrElse(link.target, Set.empty)
      s <- reasoner.hier.getOrElse(link.role, Set.empty)
      ers <- reasoner.negExistsMap.get(s)
      f <- ers.get(d)
    } todo = todo.enqueue(`Sub+`(ConceptInclusion(link.subject, f)))
    reasoner.copy(todo = todo)
  }

  private def `R∘`(link: Link, reasoner: Reasoner): Reasoner = {
    var todo = reasoner.todo
    for {
      Link(_, r2, d) <- reasoner.linksBySubject.getOrElse(link.target, Nil)
      r2s <- reasoner.hierComps.get(link.role)
      ss <- r2s.get(r2)
      s <- ss
      //s <- r2s.getOrElse(r2, Set.empty)
    } todo = todo.enqueue(Link(link.subject, s, d))
    reasoner.copy(todo = todo)
  }

  private def `R∘old`(link: Link, reasoner: Reasoner): Reasoner = { //*
    var todo = reasoner.todo
    for {
      Link(_, r2, d) <- reasoner.linksBySubject.getOrElse(link.target, Nil)
      s1 <- reasoner.hier.getOrElse(link.role, Set.empty)
      s2 <- reasoner.hier.getOrElse(r2, Set.empty)
      s <- reasoner.roleComps.getOrElse((s1, s2), Set.empty)
    } todo = todo.enqueue(Link(link.subject, s, d))
    reasoner.copy(todo = todo)
  }

  private def `R⤳`(link: Link, reasoner: Reasoner): Reasoner = reasoner.copy(todo = reasoner.todo.enqueue(link.target))

  private def saturateRoles(roleInclusions: Set[RoleInclusion]): Map[Role, Set[Role]] = { //FIXME can do this better
    val subToSuper = roleInclusions.groupBy(_.subproperty).map { case (sub, ri) => sub -> ri.map(_.superproperty) }
    def allSupers(role: Role): Set[Role] = for {
      superProp <- subToSuper.getOrElse(role, Set.empty)
      superSuperProp <- allSupers(superProp) + superProp
    } yield superSuperProp
    subToSuper.keys.map(role => role -> allSupers(role)).toMap
  }

}