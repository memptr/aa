package com.cliffc.aa.HM;

import com.cliffc.aa.type.*;
import com.cliffc.aa.util.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.*;

import static com.cliffc.aa.AA.ARG_IDX;
import static com.cliffc.aa.AA.unimpl;
import static com.cliffc.aa.type.TypeFld.Access;

// Combined Hindley-Milner and Global Constant Propagation typing.

// Complete stand-alone, for research.

// Treats HM as a Monotone Analysis Framework; converted to a worklist style.
// The type-vars are monotonically unified, gradually growing over time - and
// this is treated as the MAF lattice.  Some normal Algo-W work gets done in a
// prepass; e.g. discovering identifier sources (SSA form), and building the
// non-generative set.  Because of the non-local unification behavior type-vars
// include a "dependent Syntax" set; a set of Syntax elements put back on the
// worklist if this type unifies, beyond the expected parent and AST children.
//
// The normal HM unification steps are treated as the MAF transfer "functions",
// taking type-vars as inputs and producing new, unified, type-vars.  Because
// unification happens in-place (normal Tarjan disjoint-set union), the
// transfer "functions" are executed for side effects only, and return a
// progress flag.  The transfer functions are virtual calls on each Syntax
// element.  Some steps are empty because of the pre-pass (Let,Con).
//
// HM Bases include anything from the GCP lattice, and are generally sharper
// than e.g. 'int'.  Bases with values of '3' and "abc" are fine.  These are
// widened to the normal HM types if passed to any HM function; they remain
// sharp if returned or passed to primitives.  HM functions include the set of
// FIDXs used in the unification; this set is generally less precise than that
// from GCP.  HM function arguments that escape had their GCP type widened "as
// if" called from the most HM-general legal call site; otherwise GCP assumes
// escaping functions are never called and their arguments have unrealistic
// high flow types.
//
// HM includes polymorphic structures and fields (structural typing not duck
// typing), polymorphic nil-checking and an error type-var.  Both HM and GCP
// types fully support recursive types.
//
// HM errors keep all the not-unifiable types, all at once.  Further unifications
// with the error either add a new not-unifiable type, or unify with one of the
// prior types.  These means that types can ALWAYS unify, including nonsensical
// unifications between e.g. the constant 5 and a struct @{ x,y }.  The errors
// are reported when a type prints.
//
// Unification typically makes many temporary type-vars and immediately unifies
// them.  For efficiency, this algorithm checks to see if unification requires
// an allocation first, instead of just "allocate and unify".  The major place
// this happens is identifiers, which normally make a "fresh" copy of their
// type-var, then unify.  I use a combined "make-fresh-and-unify" unification
// algorithm there.  It is a structural clone of the normal unify, except that
// it lazily makes a fresh-copy of the left-hand-side on demand only; typically
// discovering that no fresh-copy is required.
//
// To engineer and debug the algorithm, the unification step includes a flag to
// mean "actually unify, and report a progress flag" vs "report if progress".
// The report-only mode is aggressively asserted for in the main loop; all
// Syntax elements that can make progress are asserted as on the worklist.
//
// GCP gets the normal MAF treatment, no surprises there.
//
// The combined algorithm includes transfer functions taking facts from both
// MAF lattices, producing results in the other lattice.
//
// For the GCP->HM direction, the HM 'if' has a custom transfer function
// instead of the usual one.  Unification looks at the GCP value, and unifies
// either the true arm, or the false arm, or both or neither.  In this way GCP
// allows HM to avoid picking up constraints from dead code.
//
// Also for GCP->HM, the HM ground terms or base terms include anything from
// the GCP lattice.
//
// For the HM->GCP direction, the GCP 'apply' has a customer transfer function
// where the result from a call gets lifted (JOINed) based on the matching GCP
// inputs - and the match comes from using the same HM type-var on both inputs
// and outputs.  This allows e.g. "map" calls which typically merge many GCP
// values at many applies (call sites) and thus end up typed as a Scalar to
// Scalar, to improve the GCP type on a per-call-site basis.
//
// Test case 45 demonstrates this combined algorithm, with a program which can
// only be typed using the combination of GCP and HM.
//
// BNF for the "core AA" syntax:
//    e  = number | string | primitives | (fe0 fe1*) | { id* -> fe0 } | id | id = fe0; fe1 | @{ (label = fe0)* }
//    fe = e | fe.label                 // optional field after expression
//
// BNF for the "core AA" pretty-printed types:
//    T = X | X:T | { X* -> X } | base | @{ (label = X)* } | T? | Error
//    base = any lattice element, all are nilable
//    Multiple stacked T????s collapse
//


public class HM {
  // Mapping from primitive name to PrimSyn
  static final HashMap<String,PrimSyn> PRIMSYNS = new HashMap<>();
  // Precision of cyclic GCP types
  static final int CUTOFF=1;

  static { BitsAlias.init0(); BitsFun.init0(); }

  static boolean DO_HM ;
  static boolean DO_GCP;

  static boolean HM_FREEZE;
  static boolean DO_LIFT=true;
  public static Root hm( String sprog, int rseed, boolean do_hm, boolean do_gcp ) {
    Type.RECURSIVE_MEET=0;      // Reset between failed tests
    DO_HM  = do_hm ;
    DO_GCP = do_gcp;

    for( PrimSyn prim : new PrimSyn[]{new If(), new Pair(), new EQ(), new EQ0(), new Mul(), new Add(), new Dec(), new Str(), new Triple(), new Factor(), new IsEmpty(), new NotNil()} )
      PRIMSYNS.put(prim.name(),prim);

    // Parse
    Root prog = parse( sprog );

    // Pass 0: Prep for SSA; pre-gather all the (unique) ids
    Work<Syntax> work = new Work<>(rseed);
    int cnt_syns = prog.prep_tree(null,null,work);

    // Pass 1: Everything starts high/top/leaf and falls; escaping function args are assumed high
    int work_cnt=0;
    int init_T2s = T2.CNT;  // Profiling bit
    HM_FREEZE = false;
    work_cnt+=main_work_loop(prog,work);
    assert prog.more_work(work);

    // Pass 2: Give up on the Root GCP arg types.  Drop them to the best Root
    // approximation and never lift again.
    // TODO: Combine pass2 & pass3
    prog.update_root_args(work);
    work_cnt+=main_work_loop(prog,work);
    assert prog.more_work(work);

    // Pass 3: H-M types freeze, escaping function args are assumed lowest H-M compatible and
    // GCP types continue to run downhill.
    HM_FREEZE = true;
    prog.visit((syn) -> { syn.add_val_work(null,work); return work.add(syn); }, (a,b)->null);
    work_cnt+=main_work_loop(prog,work);
    assert prog.more_work(work);

    // Pass 4: Error propagation, no types change.
    pass4(prog);

    // Profiling print
    System.out.println("Initial T2s: "+init_T2s+", Prog size: "+cnt_syns+", worklist iters: "+work_cnt+", T2s: "+(T2.CNT-init_T2s));
    return prog;
  }

  static int main_work_loop(Root prog, Work<Syntax> work) {

    int cnt=0;
    while( work.len()>0 ) {     // While work
      cnt++; assert cnt<10000;  // Check for infinite loops
      Syntax syn = work.pop();  // Get work

      // Do Hindley-Milner work
      if( DO_HM ) {
        T2 old = syn._hmt;      // Old value for progress assert
        if( syn.hm(work) ) {
          assert syn.debug_find()==old.debug_find(); // monotonic: unifying with the result is no-progress
          syn.add_hm_work(work);// Push affected neighbors on worklist
        }
      }
      // Do Global Constant Propagation work
      if( DO_GCP ) {
        Type old = syn._flow;
        Type t = syn.val(work);
        if( t!=old ) {           // Progress
          assert old.isa(t);     // Monotonic falling
          syn._flow = t;         // Update type
          if( syn._par!=null )   // Push affected neighbors on worklist
            syn._par.add_val_work(syn,work);
        }
      }

      // VERY EXPENSIVE ASSERT: O(n^2).  Every Syntax that makes progress is on the worklist
      assert prog.more_work(work);
    }
    return cnt;
  }

  static void pass4(Root prog) {
    prog.visit( syn -> {
        T2 self = syn.find();
        if( syn instanceof Field ) {
          Field fld = (Field)syn;
          T2 rec = fld._rec.find();
          if( !self.is_err() && rec.is_err2() && rec.is_struct() && rec.is_open() ) {
            rec._aliases=null;  // Turn off struct-ness for print
            self._err = "Missing field "+fld._id+" in "+rec.p();
          }
          String err = self._err;
          T2 fldt2 = rec.get(fld._id);
          if( err!=null && rec.is_struct() && !rec.is_open() && (fldt2==null || fldt2.is_err()) ) {
            if( fldt2!=null ) rec._args.remove(fld._id);
            self._err = err+" in "+rec.p();
          }
          if( rec.is_nil() || (rec._aliases != null && rec._aliases.test(0)) )
            self._err = "May be nil when loading field "+fld._id;
        }
        if( self.is_err2() && self.has_nil() )
          // If any contain nil, then we may have folded in a not-nil.
          // To preserve monotonicity, we make them all nil.
          // Example: (3.unify(notnil)).unify("abc") ==       int8     .unify("abc")  == (Error int8,"abc" ) <<-- Should be "abc"?
          // Example:  3.unify("abc")).unify(notnil) == (Error 3,"abc").unify(notnil) == (Error int8,"abc"?)
          self.add_nil();
        return null;
      }, (a,b)->null);
  }

  // Reset global statics between tests
  static void reset() {
    BitsAlias.reset_to_init0();
    BitsFun.reset_to_init0();
    PRIMSYNS.clear();
    Lambda.FUNS.clear();
    T2.reset();
    PrimSyn.reset();
  }

  // ---------------------------------------------------------------------
  // Program text for parsing
  private static int X;
  private static byte[] BUF;
  @Override public String toString() { return new String(BUF,X,BUF.length-X); }
  static Root parse( String s ) {
    X = 0;
    BUF = s.getBytes();
    Syntax prog = fterm();
    if( skipWS() != -1 ) throw unimpl("Junk at end of program: "+new String(BUF,X,BUF.length-X));
    // Inject IF at root
    return new Root(prog);
  }
  static Syntax term() {
    if( skipWS()==-1 ) return null;
    if( isDigit(BUF[X]) ) return number();
    if( BUF[X]=='"' ) return string();

    if( BUF[X]=='(' ) {         // Parse an Apply
      X++;                      // Skip paren
      Syntax fun = fterm();
      Ary<Syntax> args = new Ary<>(new Syntax[1],0);
      while( skipWS()!= ')' && X<BUF.length ) args.push(fterm());
      require(')');
      // Guarding if-nil test inserts an upcast.  This is a syntactic transform only.
      if( fun instanceof If &&
          args.at(0) instanceof Ident ) {
        Ident id = (Ident)args.at(0);
        args.set(1,new Apply(new Lambda(args.at(1), id._name),
                             new Apply(new NotNil(),new Ident(id._name))));
      }
      return new Apply(fun,args.asAry());
    }

    if( BUF[X]=='{' ) {         // Lambda of 1 or 2 args
      X++;                      // Skip paren
      Ary<String> args = new Ary<>(new String[1],0);
      while( skipWS()!='-' ) args.push(id());
      require();
      Syntax body = fterm();
      require('}');
      return new Lambda(body,args.asAry());
    }
    // Let or Id
    if( isAlpha0(BUF[X]) ) {
      String id = id();
      if( skipWS()!='=' ) {
        PrimSyn prim = PRIMSYNS.get(id); // No shadowing primitives or this lookup returns the prim instead of the shadow
        return prim==null ? new Ident(id) : prim.make(); // Make a prim copy with fresh HM variables
      }
      // Let expression; "id = term(); term..."
      X++;                      // Skip '='
      Syntax def = fterm();
      require(';');
      return new Let(id,def,fterm());
    }

    // Structure
    if( BUF[X]=='@' ) {
      X++;
      require('{');
      Ary<String>  ids = new Ary<>(String.class);
      Ary<Syntax> flds = new Ary<>(Syntax.class);
      while( skipWS()!='}' && X < BUF.length ) {
        String id = require('=',id());
        Syntax fld = fterm();
        if( fld==null ) throw unimpl("Missing term for field "+id);
        ids .push( id);
        flds.push(fld);
        if( skipWS()==',' ) X++;
      }
      require('}');
      return new Struct(ids.asAry(),flds.asAry());
    }

    throw unimpl("Unknown syntax");
  }
  // Parse a term with an optional following field.
  private static Syntax fterm() {
    Syntax term=term();
    while( true ) {
      if( term==null || skipWS()!='.' ) return term;
      X++;
      term = new Field(id(),term);
    }
  }
  private static final SB ID = new SB();
  private static String id() {
    ID.clear();
    while( X<BUF.length && isAlpha1(BUF[X]) )
      ID.p((char)BUF[X++]);
    String s = ID.toString().intern();
    if( s.length()==0 ) throw unimpl("Missing id");
    return s;
  }
  private static Syntax number() {
    if( BUF[X]=='0' ) { X++; return new Con(Type.NIL); }
    int sum=0;
    while( X<BUF.length && isDigit(BUF[X]) )
      sum = sum*10+BUF[X++]-'0';
    if( X>= BUF.length || BUF[X]!='.' )
      return new Con(TypeInt.con(sum));
    // Ambiguous '.' in: 2.3 vs 2.x (field load from a number)
    if( X+1<BUF.length && isAlpha0(BUF[X+1]) )
      return new Con(TypeInt.con(sum));
    X++;
    float f = (float)sum;
    f = f + (BUF[X++]-'0')/10.0f;
    return new Con(TypeFlt.con(f));
  }
  private static Syntax string() {
    int start = ++X;
    while( X<BUF.length && BUF[X]!='"' ) X++;
    return require('"', new Con(TypeMemPtr.make(BitsAlias.STRBITS,TypeStr.con(new String(BUF,start,X-start).intern()))));
  }
  private static byte skipWS() {
    while(true) {
      if( X == BUF.length ) return -1;
      if( X+1<BUF.length && BUF[X]=='/' && BUF[X+1]=='/' )
        while( BUF[X]!='\n' ) X++;
      if( !isWS(BUF[X]) ) return BUF[X];
      X++;
    }
  }
  private static boolean isWS    (byte c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }
  private static boolean isDigit (byte c) { return '0' <= c && c <= '9'; }
  private static boolean isAlpha0(byte c) { return ('a'<=c && c <= 'z') || ('A'<=c && c <= 'Z') || (c=='_') || (c=='*') || (c=='?') || (c=='+'); }
  private static boolean isAlpha1(byte c) { return isAlpha0(c) || ('0'<=c && c <= '9') || (c=='/'); }
  private static void require(char c) { if( skipWS()!=c ) throw unimpl("Missing '"+c+"'"); X++; }
  private static <T> T require(char c, T t) { require(c); return t; }
  private static void require() {
    skipWS();
    if( X+2 >= BUF.length || BUF[X]!= '-' || BUF[X+1]!= '>' )
      throw unimpl("Missing '->'");
    X+=2;
  }

  // ---------------------------------------------------------------------
  // Small classic tree of T2s, immutable, with sharing at the root parts.
  static class VStack implements Iterable<T2> {
    final VStack _par;
    private T2 _nongen;
    final int _d;
    VStack( VStack par, T2 nongen ) { _par=par; _nongen=nongen; _d = par==null ? 0 : par._d+1; }
    T2 nongen() {
      T2 n = _nongen.find();
      return n==_nongen ? n : (_nongen=n);
    }
    @Override public String toString() {
      // Collect dups across the forest of types
      VBitSet dups = new VBitSet();
      for( VStack vs = this; vs!=null; vs = vs._par )
        vs._nongen._get_dups(new VBitSet(),dups);
      // Now recursively print
      return str(new SB(),dups).toString();
    }
    SB str(SB sb, VBitSet dups) {
      _nongen.str(sb,new VBitSet(),dups,true);
      if( _par!=null ) _par.str(sb.p(" , "),dups);
      return sb;
    }
    @NotNull @Override public Iterator<T2> iterator() { return new Iter(); }
    private class Iter implements Iterator<T2> {
      private VStack _vstk;
      Iter() { _vstk=VStack.this; }
      @Override public boolean hasNext() { return _vstk!=null; }
      @Override public T2 next() { T2 v = _vstk.nongen(); _vstk = _vstk._par;  return v; }
    }
  }

  // ---------------------------------------------------------------------
  static abstract class Syntax implements IntSupplier {
    private static int CNT=1;
    final int _uid=CNT++;
    @Override public int getAsInt() { return _uid; }

    Syntax _par;                // Parent in the AST
    VStack _nongen;             // Non-generative type variables
    T2 _hmt;                    // Current HM type
    T2 find() {                 // U-F find
      T2 t = _hmt.find();
      return t== _hmt ? t : (_hmt =t);
    }
    T2 debug_find() { return _hmt.debug_find(); } // Find, without the roll-up

    // Dataflow types.  Varies during a run of GCP.
    Type _flow;

    // Compute a new HM type.
    // If no change, return false.
    // If a change, return always true, however:
    // - If 'work' is null do not change/set anything.
    // - If 'work' is available, update the worklist.
    abstract boolean hm(Work<Syntax> work);

    abstract void add_hm_work(@NotNull Work<Syntax> work); // Add affected neighbors to worklist

    // Compute and return (and do not set) a new GCP type for this syntax.
    abstract Type val(Work<Syntax> work);

    void add_val_work(Syntax child, @NotNull Work<Syntax> work) {} // Add affected neighbors to worklist

    // Visit whole tree recursively, applying 'map' to self, and reducing that
    // with the recursive value from all children.
    abstract <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce );

    // First pass to "prepare" the tree; does e.g. Ident lookup, sets initial
    // type-vars and counts tree size.
    abstract int prep_tree(Syntax par, VStack nongen, Work<Syntax> work);
    final void prep_tree_impl( Syntax par, VStack nongen, Work<Syntax> work, T2 t ) {
      _par = par;
      _hmt = t;
      _flow= Type.XSCALAR;
      _nongen = nongen;
      work.add(this);
    }
    void prep_lookup_deps(Ident id) {}

    // Giant Assert: True if OK; all Syntaxs off worklist do not make progress
    abstract boolean more_work(Work<Syntax> work);
    final boolean more_work_impl(Work<Syntax> work) {
      if( DO_HM && (!work.on(this) || HM_FREEZE) && hm(null) )   // Any more HM work?
        return false;           // Found HM work not on worklist or when frozen
      if( DO_GCP ) {            // Doing GCP AND
        Type t = val(null);
        assert _flow.isa(t);    // Flow is not monotonically falling
        if( !work.on(this) && _flow!=t ) // Flow progress not on worklist
          return false;
      }
      return true;
    }
    // Print for debugger
    @Override final public String toString() { return str(new SB()).toString(); }
    abstract SB str(SB sb);
    // Line-by-line print with more detail
    public String p() { return p0(new SB(), new VBitSet()).toString(); }
    final SB p0(SB sb, VBitSet dups) {
      _hmt._get_dups(new VBitSet(),dups);
      VBitSet visit = new VBitSet();
      p1(sb.i(),dups);
      if( DO_HM  ) _hmt .str(sb.p(", HMT="), visit,dups,true);
      if( DO_GCP ) _flow.str(sb.p(", GCP="),visit.clr(),null,true);
      sb.nl();
      return p2(sb.ii(2),dups).di(2);
    }
    abstract SB p1(SB sb, VBitSet dups); // Self short print
    abstract SB p2(SB sb, VBitSet dups); // Recursion print
  }

  static class Con extends Syntax {
    final Type _con;
    Con(Type con) { super(); _con=con; }
    @Override SB str(SB sb) { return p1(sb,null); }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p(_con.toString()); }
    @Override SB p2(SB sb, VBitSet dups) { return sb; }
    @Override boolean hm(Work<Syntax> work) { return false; }
    @Override Type val(Work<Syntax> work) { return _con; }
    @Override void add_hm_work( @NotNull Work<Syntax> work) { }
    @Override int prep_tree( Syntax par, VStack nongen, Work<Syntax> work ) {
      // A '0' turns into a nilable leaf.
      T2 base = _con==Type.NIL ? T2.make_nil(T2.make_leaf()) : T2.make_base(_con);
      prep_tree_impl(par, nongen, work, base);
      return 1;
    }
    @Override boolean more_work(Work<Syntax> work) { return more_work_impl(work); }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) { return map.apply(this); }
  }


  static class Ident extends Syntax {
    private final String _name; // The identifier name
    private Syntax _def;        // Cached syntax defining point
    private int _idx;           // Index in Lambda (which arg of many)
    private T2 _idt;            // Cached type var for the name in scope
    private boolean _fresh;     // True if fresh-unify; short-cut for common case of an id inside its def vs in a Let body.
    Ident(String name) { _name=name; }
    @Override SB str(SB sb) { return p1(sb,null); }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p(_name); }
    @Override SB p2(SB sb, VBitSet dups) { return sb; }
    T2 idt() {
      T2 idt = _idt.find();
      return idt==_idt ? idt : (_idt=idt);
    }
    @Override boolean hm(Work<Syntax> work) {
      T2 idt = idt(), hmt=find();
      return _fresh ? idt.fresh_unify(hmt,_nongen,work) : idt.unify(hmt,work);
    }
    @Override void add_hm_work( @NotNull Work<Syntax> work) {
      work.add(_par);
      if( _par!=null && idt().nongen_in(_par._nongen) ) // Got captured in some parent?
        idt().add_deps_work(work);  // Need to revisit dependent ids
      if( _par instanceof Apply && ((Apply)_par)._fun instanceof NotNil )
        work.add(((Apply)_par)._fun);
    }
    @Override Type val(Work<Syntax> work) {
      return _def instanceof Let ? ((Let)_def)._def._flow : ((Lambda)_def)._types[_idx];
    }
    @Override int prep_tree( Syntax par, VStack nongen, Work<Syntax> work ) {
      prep_tree_impl(par,nongen,work,T2.make_leaf());
      for( Syntax syn = _par; syn!=null; syn = syn._par )
        syn.prep_lookup_deps(this);

      // Lookup, and get the T2 type var and a pointer to the flow type.
      for( Syntax syn = _par; syn!=null; syn = syn._par ) {
        if( syn instanceof Lambda ) {
          Lambda lam = (Lambda)syn;
          if( (_idx = Util.find(lam._args,_name)) != -1 )
            return _init(lam,lam.targ(_idx),false);
        } else if( syn instanceof Let ) {
          Let let = (Let)syn;  _idx=-1;
          if( Util.eq(let._arg0,_name) )
            return _init(let,let._targ, !let._targ.nongen_in(nongen));
        }
      }
      throw new RuntimeException("Parse error, "+_name+" is undefined in "+_par);
    }
    private int _init(Syntax def,T2 idt, boolean fresh) {
      _def = def; _idt = idt; _fresh=fresh; return 1; }
    @Override boolean more_work(Work<Syntax> work) { return more_work_impl(work); }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) { return map.apply(this); }
  }


  static class Lambda extends Syntax {
    // Map from FIDXs to Lambdas
    static final NonBlockingHashMapLong<Lambda> FUNS = new NonBlockingHashMapLong<>();
    final String[] _args;       // Lambda argument names
    final Syntax _body;         // Lambda body
    final T2[]      _targs;     // HM argument types
    final Type[]    _types;     // Flow argument types
    final Ident[][] _refs;      // Identifiers referring to this argument
    final int _fidx;            // Unique function idx
    final Ary<Apply> _applys;   // Applys using this Lambda
    static final String[] ARGNAMES = new String[]{" x"," y"," z"};

    Lambda(Syntax body, String... args) {
      _args=args;
      _body=body;
      // Type variables for all arguments
      _targs = new T2[args.length];
      for( int i=0; i<args.length; i++ ) _targs[i] = T2.make_leaf();
      // Flow types for all arguments
      _types = new Type[args.length];
      for( int i=0; i<args.length; i++ ) _types[i] = Type.XSCALAR;
      // Idents referring to this argument
      _refs = new Ident[args.length][];
      _applys = new Ary<>(Apply.class);
      // A unique FIDX for this Lambda
      _fidx = BitsFun.new_fidx();
      FUNS.put(_fidx,this);
      _flow = TypeFunPtr.makex(BitsFun.make0(_fidx),_args.length,Type.ANY,Type.XSCALAR);
    }
    @Override SB str(SB sb) {
      sb.p("{ ");
      for( String arg : _args ) sb.p(arg).p(' ');
      return _body.str(sb.p("-> ")).p(" }");
    }
    @Override SB p1(SB sb, VBitSet dups) {
      sb.p("{ ");
      for( int i=0; i<_args.length; i++ ) {
        sb.p(_args[i]);
        if( DO_HM  ) _targs[i].str(sb.p(", HMT=" ),new VBitSet(),dups,true);
        if( DO_GCP ) sb.p(", GCP=").p(_types[i]);
        sb.nl().i().p("  ");
      }
      return sb.p(" -> ... } ");
    }
    @Override SB p2(SB sb, VBitSet dups) { return _body.p0(sb,dups); }
    T2 targ(int i) { T2 targ = _targs[i].find(); return targ==_targs[i] ? targ : (_targs[i]=targ); }
    @Override boolean hm(Work<Syntax> work) {
      // The normal lambda work
      T2 old = find();
      boolean progress = false;
      for( int i=0; i<_targs.length; i++ )
        progress |= old.arg(ARGNAMES[i]).unify(targ(i),work);
      return old.arg("ret").unify(_body.find(),work) | progress;
    }
    @Override void add_hm_work( @NotNull Work<Syntax> work) { throw unimpl(); }
    @Override Type val(Work<Syntax> work) {
      // Just wrap a function around the body return
      return TypeFunPtr.makex(BitsFun.make0(_fidx),_args.length,Type.ANY,_body._flow);
    }
    // Meet the formal argument# with a new Apply callsite actual arg.
    void arg_meet(int argn, Type cflow, Work<Syntax> work) {
      Type old = _types[argn];
      Type mt = old.meet(cflow);
      if( mt==old ) return;     // No change
      _types[argn]=mt;          // Yes change, update
      work.add(_refs[argn]);    // And revisit referrers
      if( this instanceof PrimSyn ) work.add(this); // Primitives recompute
    }

    // Ignore arguments, and return body type for a particular call site.  Very conservative.
    Type apply(Type[] flows) { return _body._flow; }
    @Override void add_val_work(Syntax child, @NotNull Work<Syntax> work) {
      work.add(this);
      // Body changed, all Apply sites need to recompute
      work.addAll(_applys);
    }
    @Override int prep_tree( Syntax par, VStack nongen, Work<Syntax> work ) {
      // Prep self
      prep_tree_impl(par,nongen,work,T2.make_leaf());
      // Extend the nongen set by the new variables
      VStack vs = nongen;
      for( T2 targ : _targs ) vs = new VStack(vs, targ);
      // Prep the body
      int cnt = _body.prep_tree(this,vs,work) + 1;
      // Go ahead and pre-unify with a required function
      T2[] targs = Arrays.copyOf(_targs,_targs.length+1);
      targs[_targs.length] = _body.find();
      // Widen all constant base inputs for normal functions, but not for the
      // hidden internal one made for not-nil.
      find().unify(T2.make_fun(BitsFun.make0(_fidx), targs),work);
      return cnt;
    }
    @Override void prep_lookup_deps(Ident id) {
      for( int i=0; i<_args.length; i++ )
        if( Util.eq(_args[i],id._name) ) {
          // Deps are based on T2, and trigger when the HM types change
          _targs[i].push_update(id); //
          // Refs are based on Syntax, basically a wimpy SSA for for GCP propagation
          Ident[] refs = _refs[i];
          if( refs==null ) _refs[i] = refs = new Ident[0];
          // Hard linear-time append ident to the end.  Should be very limited in size.
          _refs[i] = refs = Arrays.copyOf(refs,refs.length+1);
          refs[refs.length-1] = id;
        }
    }
    @Override boolean more_work(Work<Syntax> work) {
      if( !more_work_impl(work) ) return false;
      return _body.more_work(work);
    }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) {
      T rez = map.apply(this);
      // Primitives have no body
      return _body==null ? rez : reduce.apply(rez,_body.visit(map,reduce));
    }
  }

  static class Let extends Syntax {
    final String _arg0;
    final Syntax _def, _body;
    T2 _targ;
    Syntax[] _refs;               // Identifiers referring here
    Let(String arg0, Syntax def, Syntax body) { _arg0=arg0; _body=body; _def=def; _targ=T2.make_leaf(); _refs=new Ident[0]; }
    @Override SB str(SB sb) { return _body.str(_def.str(sb.p(_arg0).p(" = ")).p("; ")); }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p(_arg0).p(" = ... ; ..."); }
    @Override SB p2(SB sb, VBitSet dups) { _def.p0(sb,dups); return _body.p0(sb,dups); }
    @Override boolean hm(Work<Syntax> work) { return false;  }
    @Override void add_hm_work( @NotNull Work<Syntax> work) {
      work.add(_par);
      work.add(_body);
      work.add(_def);
      work.addAll(_def.find()._deps);
    }
    @Override Type val(Work<Syntax> work) { return _body._flow; }
    // Definition changed; all dependents need to revisit
    @Override void add_val_work( Syntax child, @NotNull Work<Syntax> work) {
      if( child==_def ) work.add(_refs);
      else              work.add(this);
    }

    @Override int prep_tree( Syntax par, VStack nongen, Work<Syntax> work ) {
      prep_tree_impl(par,nongen,work,_body._hmt);
      int cnt = _body.prep_tree(this,           nongen       ,work) +
                _def .prep_tree(this,new VStack(nongen,_targ),work);
      _hmt = _body._hmt;            // Unify 'Let._hmt' with the '_body'
      _targ.unify(_def.find(),work);
      return cnt+1;
    }
    @Override void prep_lookup_deps(Ident id) {
      if( Util.eq(id._name,_arg0) ) {
        // Deps are based on T2, and trigger when the HM types change
        _targ.push_update(id);
        // Refs are based on Syntax, basically a wimpy SSA for for GCP propagation
        // Hard linear-time append ident to the end.  Should be very limited in size.
        _refs = Arrays.copyOf(_refs,_refs.length+1);
        _refs[_refs.length-1] = id;
      }
    }
    @Override boolean more_work(Work<Syntax> work) {
      if( !more_work_impl(work) ) return false;
      return _body.more_work(work) && _def.more_work(work);
    }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) {
      T rez  = map.apply(this);
      T def  = reduce.apply(rez,_def .visit(map,reduce));
      return   reduce.apply(def,_body.visit(map,reduce));
    }
  }


  static class Apply extends Syntax {
    final Syntax _fun;
    final Syntax[] _args;
    Apply(Syntax fun, Syntax... args) { _fun = fun; _args = args; }
    @Override SB str(SB sb) {
      _fun.str(sb.p("(")).p(" ");
      for( Syntax arg : _args )
        arg.str(sb).p(" ");
      return sb.unchar().p(")");
    }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p("(...)"); }
    @Override SB p2(SB sb, VBitSet dups) {
      _fun.p0(sb,dups);
      for( Syntax arg : _args ) arg.p0(sb,dups);
      return sb;
    }

    // Unifiying these: make_fun(this.arg0 this.arg1 -> new     )
    //                      _fun{_fun.arg0 _fun.arg1 -> _fun.rez}
    @Override boolean hm(Work<Syntax> work) {
      // Progress if:
      //   _fun is not a function
      //   any arg-pair-unifies make progress
      //   this-unify-_fun.return makes progress
      T2 tfun = _fun.find();
      if( !tfun.is_fun() ) {    // Not a function, so progress
        if( work==null ) return true; // Will-progress & just-testing
        T2[] targs = new T2[_args.length+1];
        for( int i=0; i<_args.length; i++ )
          targs[i] = _args[i].find();
        targs[_args.length] = find(); // Return
        T2 nfun = T2.make_fun(BitsFun.EMPTY, targs);
        return tfun.unify(nfun,work);
      }

      // Check for progress amongst arg pairs
      boolean progress = false;
      for( int i=0; i<_args.length; i++ ) {
        progress |= tfun.arg(Lambda.ARGNAMES[i]).unify(_args[i].find(),work);
        if( progress && work==null ) return true; // Will-progress & just-testing early exit
        tfun=tfun.find();
      }
      // Check for progress on the return
      progress |= find().unify(tfun.arg("ret"),work);
      return progress;
    }
    @Override void add_hm_work( @NotNull Work<Syntax> work) {
      work.add(_par);
      work.add(_args);
    }

    // Record call-site actual arguments on lambda formals
    Type _val( Work<Syntax> work ) {
      Type flow = _fun._flow;
      if( !(flow instanceof TypeFunPtr) ) return flow.oob(Type.SCALAR);
      TypeFunPtr tfp = (TypeFunPtr)flow;
      if( tfp._fidxs == BitsFun.EMPTY || (DO_HM && !_fun.find().is_fun()) )
        return Type.XSCALAR; // Nothing being called, stay high
      if( work!=null &&
          // 'ALL' happens from functions being passed in via Root (top-level
          // arguments), and are not any local function being analyzed.
          tfp._fidxs != BitsFun.NZERO )
        for( int fidx : tfp._fidxs ) {
          Lambda lambda = Lambda.FUNS.get(fidx);
          if( lambda._applys.find(this) == -1 ) {
            // new call site for lambda; all args must meet into this lambda;
            lambda._applys.push(this);
            for( int i=0; i<_args.length; i++ )
              lambda.arg_meet(i,_args[i]._flow,work);
            if( lambda instanceof PrimSyn ) // Normal lambdas update from their _body alone
              work.add(lambda);             // But primitives re-apply arguments
          }
      }
      return flow;
    }

    @Override Type val(Work<Syntax> work) {
      Type flow = _val(work);
      if( !(flow instanceof TypeFunPtr) ) return flow;
      TypeFunPtr tfp = (TypeFunPtr)flow;
      // Have some functions, meet over their returns.
      Type ret = tfp._ret;
      // Attempt to lift the result, based on HM types.
      if( DO_LIFT && DO_HM ) {
        assert _flow.isa(ret) ; // Monotonic...
        Type lift = hm_apply_lift(find(),ret);
        assert _flow.isa(lift) ; // Monotonic...
        ret = lift; // Upgrade
      }
      return ret;
    }

    // Walk the input HM type and CCP flow type in parallel and create a
    // mapping.  Then walk the output HM type and CCP flow type in parallel,
    // and join output CCP types with the matching input CCP type.
    Type hm_apply_lift(T2 rezt2, Type ret) {
      // Walk the input types, finding all the Leafs.  Repeats of the same Leaf
      // has its flow Types MEETed.
      T2.T2MAP.clear();
      for( Syntax arg : _args )
        { T2.WDUPS.clear(true); arg.find().walk_types_in(arg._flow); }

      // Then walk the output types, building a corresponding flow Type, but
      // matching against input Leafs.  If HM_FREEZE Leafs must match
      // exactly, replacing the input flow Type with the corresponding flow
      // Type.  If !HM_FREEZE, replace with a meet of flow types.
      T2.WDUPS.clear(true);  T2.WBS.clear();
      Type lift = rezt2.walk_types_out(ret, this);
      if( lift==ret ) return ret; // No progress
      return ret.join(lift);      // Lifted result
    }

    @Override void add_val_work( Syntax child, @NotNull Work<Syntax> work) {
      // push self, because self returns the changed-functions' ret
      if( child==_fun ) { work.add(this); return; }
      if( DO_LIFT && DO_HM ) work.add(this); // Child input fell, parent may lift less

      // Check for some Lambdas present
      Type flow = _fun._flow;
      if( !(flow instanceof TypeFunPtr) ) return;
      TypeFunPtr tfp = (TypeFunPtr)flow;
      if( tfp._fidxs == BitsFun.EMPTY ) return;

      // child arg to a call-site changed; find the arg#;
      int argn = Util.find(_args,child);

      // visit all Lambdas; meet the child flow into the Lambda arg#
      if( argn != -1 && tfp._fidxs != BitsFun.NZERO )
        for( int fidx : tfp._fidxs )
          Lambda.FUNS.get(fidx).arg_meet(argn,child._flow,work);
    }

    @Override int prep_tree(Syntax par, VStack nongen, Work<Syntax> work) {
      prep_tree_impl(par,nongen,work,T2.make_leaf());
      int cnt = 1+_fun.prep_tree(this,nongen,work);
      for( Syntax arg : _args ) cnt += arg.prep_tree(this,nongen,work);
      return cnt;
    }
    @Override boolean more_work(Work<Syntax> work) {
      if( !more_work_impl(work) ) return false;
      if( !_fun.more_work(work) ) return false;
      for( Syntax arg : _args ) if( !arg.more_work(work) ) return false;
      return true;
    }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) {
      T slf = map.apply(this);
      T rez = reduce.apply(slf,_fun.visit(map,reduce));
      for( Syntax arg : _args )
        rez = reduce.apply(rez,arg.visit(map,reduce));
      return rez;
    }

  }


  // -----------------

  // Root is nearly, but not quite, an Apply.  The _fun field does NOT have to
  // hold a Lambda; it can be anything.  Hence, the _hmt is just a copy of the
  // _fun field (and NOT the function's result - which is what Apply does).
  //
  static class Root extends Apply {
    static final Type[] FLOWS = new Type[0];
    Root(Syntax body) { super(body); }
    @Override SB str(SB sb) { return _fun.str(sb); }
    @Override boolean hm(final Work<Syntax> work) { return find().unify(_fun.find(),work); }
    @Override void add_hm_work( @NotNull Work<Syntax> work) { }
    @Override Type val(Work<Syntax> work) {
      _val(work);
      Type flow = _fun._flow;
      T2 fun = _fun.find();
      if( !fun.is_fun() ) return flow;
      if( !(flow instanceof TypeFunPtr) ) return flow;
      TypeFunPtr tfp = (TypeFunPtr)flow;

      // Have some functions, meet over their returns.
      Type ret = tfp._ret;
      // Attempt to lift the result, based on HM types.
      if( DO_LIFT && DO_HM ) {
        Type lift = hm_apply_lift(fun.arg("ret"),ret);
        ret = lift; // Upgrade
      }
      return tfp.make_from(Type.ANY,ret);
    }

    // After GCP stability, we guess (badly) that all escaping functions
    // are called by folks outside of Root with the worst possible args.
    // TODO: Force programmer type annotations for module entry points.
    private static final VBitSet RVISIT = new VBitSet();
    void update_root_args(Work<Syntax> work) {
      if( DO_HM ) {
        RVISIT.clear();
        _widen_bases(false,find());
      }
      // If an argument changes type, adjust the lambda arg types
      Type flow = _fun._flow;
      if( DO_GCP && !flow.above_center() ) {
        RVISIT.clear();
        _walk_root_funs(flow,work);
      }

    }
    // TODO: Type walker
    private static void _walk_root_funs( Type flow, Work<Syntax> work) {
      if( RVISIT.tset(flow._uid) ) return;
        // Find any functions
      if( flow instanceof TypeFunPtr ) {
        if( ((TypeFunPtr)flow)._fidxs.test(1) ) return; // All of them
        // Meet the actuals over the formals.
        for( int fidx : ((TypeFunPtr)flow)._fidxs ) {
          Lambda fun = Lambda.FUNS.get(fidx);
          for( int i=0; i<fun._types.length; i++ ) {
            // GCP external argument limited to HM compatible type
            Type aflow = DO_HM ? fun.targ(i).as_flow() : Type.SCALAR;
            fun.arg_meet(i,aflow,work);
          }
          if( fun instanceof PrimSyn ) work.add(fun);
        }
      }

      // recursively walk structures for nested functions
      if( flow instanceof TypeMemPtr ) {
        TypeMemPtr tmp = (TypeMemPtr)flow;
        if( tmp._obj instanceof TypeStr ) return;
        TypeStruct ts = ((TypeStruct)tmp._obj);
        for( TypeFld fld : ts.flds() )
          _walk_root_funs(fld._t,work);
      }
    }
    // TODO: T2 walker
    // If a root-escaping function has Base inputs, widen them to allow
    // anything from that Base class.  E.g., typed as taking a "abc" input is
    // widened to any string.
    private static void _widen_bases(boolean funarg, T2 t2) {
      if( RVISIT.tset(t2._uid) ) return;
      if( t2.is_base() && funarg ) t2._flow=t2._flow.widen();
      if( t2._args != null ) {
        funarg = t2.is_fun();
        for( String arg : t2._args.keySet() )
          if( !Util.eq(arg,"ret") ) // Do not walk function returns
            _widen_bases(funarg,t2.arg(arg));
      }
    }

    // Expand functions to full signatures, recursively.
    // Used by testing.
    private static final VBitSet ADD_SIG = new VBitSet();
    Type flow_type() { ADD_SIG.clear(); return add_sig(_flow); }
    private static Type add_sig(Type t) {
      if( ADD_SIG.tset(t._uid) ) return t;
      if( t instanceof TypeFunPtr ) {
        TypeFunPtr fun = (TypeFunPtr)t;
        Type rez = Type.XSCALAR;
        if( fun._fidxs.test(1) ) rez = Type.SCALAR;
        else
          for( int fidx : fun._fidxs )
            rez = rez.meet(Lambda.FUNS.get(fidx).apply(FLOWS));
        Type rez2 = add_sig(rez);
        return TypeFunSig.make(TypeStruct.EMPTY,rez2);
      } else {
        return t;
      }
    }
  }


  // Structure or Records.
  static class Struct extends Syntax {
    final int _alias;
    final String[]  _ids;
    final Syntax[] _flds;
    Struct( String[] ids, Syntax[] flds ) {
      _ids=ids;
      _flds=flds;
      // Make a TMP
      _alias = BitsAlias.new_alias(BitsAlias.REC);
    }
    @Override SB str(SB sb) {
      sb.p("@{").p(_alias);
      for( int i=0; i<_ids.length; i++ ) {
        sb.p(' ').p(_ids[i]).p(" = ");
        _flds[i].str(sb);
        if( i < _ids.length-1 ) sb.p(',');
      }
      return sb.p("}");
    }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p("@{").p(_alias).p(" ... } "); }
    @Override SB p2(SB sb, VBitSet dups) {
      for( int i=0; i<_ids.length; i++ )
        _flds[i].p0(sb.i().p(_ids[i]).p(" = ").nl(),dups);
      return sb;
    }
    @Override boolean hm(Work<Syntax> work) {
      // Force result to be a struct with at least these fields.
      // Do not allocate a T2 unless we need to pick up fields.
      T2 rec = find();
      assert check_fields(rec);
      rec.push_update(this);

      // Unify existing fields.  Ignore extras on either side.
      boolean progress = false;
      for( int i=0; i<_ids.length; i++ ) {
        T2 fld = rec.arg(_ids[i]);
        if( fld!=null ) progress |= fld.unify(_flds[i].find(),work);
        if( work==null && progress ) return true;
      }

      return progress;
    }
    // Extra fields are unified with ERR since they are not created here:
    // error to load from a non-existing field
    private boolean check_fields(T2 rec) {
      if( rec._args != null )
        for( String id : rec._args.keySet() )
          if( Util.find(_ids,id)==-1 && !rec.arg(id).is_err() )
            return false;
      return true;
    }
    @Override void add_hm_work( @NotNull Work<Syntax> work) {
      work.add(_par);
      work.add(_flds);
    }
    @Override Type val(Work<Syntax> work) {
      TypeFld[] flds = new TypeFld[_flds.length+1];
      flds[0] = TypeFld.NO_DISP;
      for( int i=0; i<_flds.length; i++ )
        flds[i+1] = TypeFld.make(_ids[i],_flds[i]._flow);
      TypeStruct tstr = TypeStruct.make(flds);
      TypeStruct t2 = tstr.approx(CUTOFF,BitsAlias.make0(_alias));
      return TypeMemPtr.make(_alias,t2);
    }
    @Override void add_val_work(Syntax child, @NotNull Work<Syntax> work) { work.add(this); }

    @Override int prep_tree(Syntax par, VStack nongen, Work<Syntax> work) {
      prep_tree_impl(par, nongen, work, T2.make_open_struct(BitsAlias.make0(_alias),null,null));
      int cnt = 1;              // One for self
      T2[] t2s = new T2[_ids.length];
      if( _ids.length!=0 ) _hmt._args = new NonBlockingHashMap<>();
      assert _hmt._deps==null;
      for( int i=0; i<_ids.length; i++ ) { // Prep all sub-fields
        cnt += _flds[i].prep_tree(this,nongen,work);
        t2s[i] = _flds[i].find();
        _hmt._args.put(_ids[i],t2s[i]);
      }
      return cnt;
    }
    @Override boolean more_work(Work<Syntax> work) {
      if( !more_work_impl(work) ) return false;
      for( Syntax fld : _flds )
        if( !fld.more_work(work) )
          return false;
      return true;
    }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) {
      T rez = map.apply(this);
      for( Syntax fld : _flds )
        rez = reduce.apply(rez,fld.visit(map,reduce));
      return rez;
    }
  }

  // Field lookup in a Struct
  static class Field extends Syntax {
    final String _id;
    final Syntax _rec;
    Field( String id, Syntax str ) { _id=id; _rec =str; }
    @Override SB str(SB sb) { return _rec.str(sb).p(".").p(_id); }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p(".").p(_id); }
    @Override SB p2(SB sb, VBitSet dups) { return _rec.p0(sb,dups); }
    @Override boolean hm(Work<Syntax> work) {
      T2 self = find();
      T2 rec = _rec.find();
      rec.push_update(this);
      T2 fld = rec.arg(_id);
      if( fld!=null )           // Unify against a pre-existing field
        return fld.unify(self, work);

      // Add struct-ness if possible
      if( !rec.is_struct() && !rec.is_nil() ) {
        rec._open = true;
        rec._aliases = BitsAlias.EMPTY;
        if( rec._args==null ) rec._args = new NonBlockingHashMap<>();
        assert rec.is_struct();
      }
      // Add the field
      if( rec.is_struct() && rec.is_open() ) {
        rec.add_fld(_id,self,work);
        return true;
      }
      // Closed/non-record, field is missing
      if( self._err!=null ) return false;
      self._err = "Missing field "+_id;
      return true;
    }
    @Override void add_hm_work( @NotNull Work<Syntax> work) {
      work.add(_par);
      work.add(_rec);
      _rec.add_hm_work(work);
    }
    @Override Type val(Work<Syntax> work) {
      Type trec = _rec._flow;
      if( trec.above_center() || trec==Type.NIL ) return Type.XSCALAR;
      if( trec instanceof TypeMemPtr ) {
        TypeMemPtr tmp = (TypeMemPtr)trec;
        if( tmp._obj instanceof TypeStruct ) {
          TypeStruct tstr = (TypeStruct)tmp._obj;
          TypeFld fld = tstr.get(_id);
          if( fld!=null ) return fld._t; // Field type
        }
        if( tmp._obj.above_center() ) return Type.XSCALAR;
      }
      return Type.SCALAR;
    }
    @Override void add_val_work(Syntax child, @NotNull Work<Syntax> work) { work.add(this); }
    @Override int prep_tree(Syntax par, VStack nongen, Work<Syntax> work) {
      prep_tree_impl(par, nongen, work, T2.make_leaf());
      return _rec.prep_tree(this,nongen,work)+1;
    }
    @Override boolean more_work(Work<Syntax> work) {
      if( !more_work_impl(work) ) return false;
      return _rec.more_work(work);
    }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) {
      T rez = map.apply(this);
      return reduce.apply(rez,_rec.visit(map,reduce));
    }
  }


  abstract static class PrimSyn extends Lambda {
    static int PAIR_ALIAS, TRIPLE_ALIAS;
    static void reset() {
      PAIR_ALIAS   = BitsAlias.new_alias(BitsAlias.REC);
      TRIPLE_ALIAS = BitsAlias.new_alias(BitsAlias.REC);
    }
    static T2 BOOL (){ return T2.make_base(TypeInt.BOOL); }
    static T2 INT64(){ return T2.make_base(TypeInt.INT64); }
    static T2 STRP (){ return T2.make_base(TypeMemPtr.STRPTR); }
    static T2 FLT64(){ return T2.make_base(TypeFlt.FLT64); }
    abstract String name();
    private static final String[][] IDS = new String[][] {
      null,
      {"x"},
      {"x","y"},
      {"x","y","z"},
    };
    PrimSyn(T2 ...t2s) {
      super(null,IDS[t2s.length-1]);
      _hmt = T2.make_fun(BitsFun.make0(_fidx), t2s).fresh();
      for( int i=0; i<_targs.length; i++ )
        _targs[i] = _hmt.arg(Lambda.ARGNAMES[i]).push_update(this);
    }
    abstract PrimSyn make();
    @Override int prep_tree(Syntax par, VStack nongen, Work<Syntax> work) {
      prep_tree_impl(par,nongen,work, _hmt);
      return 1;
    }
    @Override boolean hm(Work<Syntax> work) {
      return false;
    }
    @Override void add_hm_work( @NotNull Work<Syntax> work) {
      if( find().is_err() )
        throw unimpl();         // Untested; should be ok
    }
    @Override Type val(Work<Syntax> work) {
      assert _body==null;
      Type ret = apply(_types);
      return TypeFunPtr.makex(BitsFun.make0(_fidx),_args.length,Type.ANY,ret);
    }

    @Override boolean more_work(Work<Syntax> work) { return more_work_impl(work); }
    @Override SB str(SB sb){ return sb.p(name()); }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p(name()); }
    @Override SB p2(SB sb, VBitSet dups) { return sb; }
  }


  // Pair
  static class Pair extends PrimSyn {
    @Override String name() { return "pair"; }
    static private T2 var1,var2;
    public Pair() {
      super(var1=T2.make_leaf(),var2=T2.make_leaf(),T2.make_open_struct(BitsAlias.make0(PAIR_ALIAS),new String[]{"0","1"},new T2[]{var1,var2}));
    }
    @Override PrimSyn make() { return new Pair(); }
    @Override Type apply(Type[] flows) {
      TypeFld[] ts = new TypeFld[flows.length+1];
      ts[0] = TypeFld.NO_DISP;  // Display
      for( int i=0; i<flows.length; i++ ) ts[i+1] = TypeFld.make_tup(flows[i],ARG_IDX+i);
      TypeStruct tstr = TypeStruct.make(ts);
      TypeStruct ts2 = tstr.approx(CUTOFF,BitsAlias.make0(PAIR_ALIAS));
      return TypeMemPtr.make(PAIR_ALIAS,ts2);
    }
  }


  // Triple
  static class Triple extends PrimSyn {
    @Override String name() { return "triple"; }
    static private T2 var1,var2,var3;
    public Triple() { super(var1=T2.make_leaf(),var2=T2.make_leaf(),var3=T2.make_leaf(),T2.make_open_struct(BitsAlias.make0(TRIPLE_ALIAS),new String[]{"0","1","2"},new T2[]{var1,var2,var3})); }
    @Override PrimSyn make() { return new Triple(); }
    @Override Type apply(Type[] flows) {
      TypeFld[] ts = new TypeFld[flows.length+1];
      ts[0] = TypeFld.NO_DISP;  // Display
      for( int i=0; i<flows.length; i++ ) ts[i+1] = TypeFld.make_tup(flows[i],ARG_IDX+i);
      TypeStruct tstr = TypeStruct.make(ts);
      TypeStruct ts2 = tstr.approx(CUTOFF,BitsAlias.make0(TRIPLE_ALIAS));
      return TypeMemPtr.make(TRIPLE_ALIAS,ts2);
    }
  }

  // Special form of a Lambda body for IF which changes the H-M rules.
  // None-executing paths do not unify args.
  static class If extends PrimSyn {
    @Override String name() { return "if"; }
    public If() { super(T2.make_leaf(),T2.make_leaf(),T2.make_leaf(),T2.make_leaf()); }
    @Override PrimSyn make() { return new If(); }
    @Override boolean hm(Work<Syntax> work) {
      T2 rez = find().arg("ret");
      // GCP helps HM: do not unify dead control paths
      if( DO_GCP ) {            // Doing GCP during HM
        Type pred = _types[0];
        if( pred == TypeInt.FALSE || pred == Type.NIL || pred==Type.XNIL )
          return rez.unify(targ(2),work); // Unify only the false side
        if( pred.above_center() ? !pred.may_nil() : !pred.must_nil() )
          return rez.unify(targ(1),work);
        if( pred.above_center() ) // Wait until predicate falls
          return false;
      }
      // Unify both sides with the result
      return
        rez       .unify(targ(1),work) |
        rez.find().unify(targ(2),work);
    }
    @Override Type apply( Type[] flows) {
      Type pred= flows[0];
      Type t1  = flows[1];
      Type t2  = flows[2];
      // Conditional Constant Propagation: only prop types from executable sides
      if( pred == TypeInt.FALSE || pred == Type.NIL || pred==Type.XNIL )
        return t2;              // False only
      if( pred.above_center() ) // Delay any values
        return Type.XSCALAR;    // t1.join(t2);     // Join of either
      if( !pred.must_nil() )    // True only
        return t1;
      // Could be either, so meet
      return t1.meet(t2);
    }
  }

  // EQ
  static class EQ extends PrimSyn {
    @Override String name() { return "eq"; }
    static private T2 var1;
    public EQ() { super(var1=T2.make_leaf(),var1,BOOL()); }
    @Override PrimSyn make() { return new EQ(); }
    @Override Type apply( Type[] flows) {
      Type x0 = flows[0];
      Type x1 = flows[1];
      if( x0.above_center() || x1.above_center() ) return TypeInt.BOOL.dual();
      if( x0.is_con() && x1.is_con() && x0==x1 )
        return TypeInt.TRUE;
      // TODO: Can also know about nil/not-nil
      return TypeInt.BOOL;
    }
  }

  // EQ0
  static class EQ0 extends PrimSyn {
    @Override String name() { return "eq0"; }
    public EQ0() { super(INT64(),BOOL()); }
    @Override PrimSyn make() { return new EQ0(); }
    @Override Type apply( Type[] flows) {
      Type pred = flows[0];
      if( pred.above_center() )
        return pred.may_nil() ? TypeInt.BOOL.dual() : TypeInt.FALSE;
      if( pred==Type.ALL ) return TypeInt.BOOL;
      if( pred == TypeInt.FALSE || pred == Type.NIL || pred==Type.XNIL )
        return TypeInt.TRUE;
      if( pred.meet(Type.NIL)!=pred )
        return TypeInt.FALSE;
      return TypeInt.BOOL;
    }
  }

  static class IsEmpty extends PrimSyn {
    @Override String name() { return "isempty"; }
    public IsEmpty() { super(STRP(),BOOL()); }
    @Override PrimSyn make() { return new IsEmpty(); }
    @Override Type apply( Type[] flows) {
      Type pred = flows[0];
      if( pred.above_center() ) return TypeInt.BOOL.dual();
      TypeObj to;
      if( pred instanceof TypeMemPtr && (to=((TypeMemPtr)pred)._obj) instanceof TypeStr && to.is_con() )
        return TypeInt.con(to.getstr().isEmpty() ? 1 : 0);
      return TypeInt.BOOL;
    }
  }

  // Remove a nil from a struct after a guarding if-test
  static class NotNil extends PrimSyn {
    @Override String name() { return " notnil"; }
    public NotNil() { super(T2.make_leaf(),T2.make_leaf()); }
    @Override PrimSyn make() { throw unimpl(); /*return new NotNil(); */}
    @Override boolean hm(Work<Syntax> work) {
      T2 arg = targ(0);
      T2 fun = find(); assert fun.is_fun();
      T2 ret = fun.arg("ret");
      // If the arg is already nil-checked, can be a nilable of a nilable.
      if( arg==ret ) return false;
      // Already an expanded nilable
      if( arg.is_nil() && arg.arg("?") == ret ) return false;
      // Already an expanded nilable with base
      if( arg.is_base() && ret.is_base() ) {
        assert !arg.is_open() && !ret.is_open();
        assert arg._flow == ret._flow.meet(Type.NIL);
        return false;
      }
      // Already an expanded nilable with struct
      if( arg.is_struct() && ret.is_struct() ) {
        boolean progress=false;
        BitsAlias mt = arg._aliases.meet(ret._aliases);
        BitsAlias amt = mt.set(0);
        BitsAlias rmt = mt.clear(0);
        if( amt != arg._aliases ) { arg._aliases=amt; progress=true; }
        if( rmt != ret._aliases ) { ret._aliases=rmt; progress=true; }
        return T2.unify_flds(arg,ret,work,true) | progress;
      }
      if( work==null ) return true;
      // If the arg is already nil-checked, can be a nilable of a nilable.
      if( arg.is_nil() && ret.is_nil() )
        return arg.unify(ret,work);
      // Unify with arg with a nilable version of the ret.
      return T2.make_nil(ret).find().unify(arg,work);
    }
    @Override Type apply( Type[] flows) {
      Type val = flows[0];
      if( val==Type.XNIL ) return Type.XSCALAR; // Weird case of not-nil nil
      return val.join(Type.NSCALR);
    }
  }

  // multiply
  static class Mul extends PrimSyn {
    @Override String name() { return "*"; }
    public Mul() { super(INT64(),INT64(),INT64()); }
    @Override PrimSyn make() { return new Mul(); }
    @Override Type apply( Type[] flows) {
      Type t0 = flows[0];
      Type t1 = flows[1];
      if( t0.above_center() || t1.above_center() )
        return TypeInt.INT64.dual();
      if( t0 instanceof TypeInt && t1 instanceof TypeInt ) {
        if( t0.is_con() && t0.getl()==0 ) return TypeInt.ZERO;
        if( t1.is_con() && t1.getl()==0 ) return TypeInt.ZERO;
        if( t0.is_con() && t1.is_con() )
          return TypeInt.con(t0.getl()*t1.getl());
      }
      return TypeInt.INT64;
    }
  }

  // add integers
  static class Add extends PrimSyn {
    @Override String name() { return "+"; }
    public Add() { super(INT64(),INT64(),INT64()); }
    @Override PrimSyn make() { return new Add(); }
    @Override Type apply( Type[] flows) {
      Type t0 = flows[0];
      Type t1 = flows[1];
      if( t0.above_center() || t1.above_center() )
        return TypeInt.INT64.dual();
      if( t0 instanceof TypeInt && t1 instanceof TypeInt ) {
        if( t0.is_con() && t1.is_con() )
          return TypeInt.con(t0.getl()+t1.getl());
      }
      return TypeInt.INT64;
    }
  }

  // decrement
  static class Dec extends PrimSyn {
    @Override String name() { return "dec"; }
    public Dec() { super(INT64(),INT64()); }
    @Override PrimSyn make() { return new Dec(); }
    @Override Type apply( Type[] flows) {
      Type t0 = flows[0];
      if( t0.above_center() ) return TypeInt.INT64.dual();
      if( t0 instanceof TypeInt && t0.is_con() )
        return TypeInt.con(t0.getl()-1);
      return TypeInt.INT64;
    }
  }

  // int->str
  static class Str extends PrimSyn {
    @Override String name() { return "str"; }
    public Str() { super(INT64(),STRP()); }
    @Override PrimSyn make() { return new Str(); }
    @Override Type apply( Type[] flows) {
      Type i = flows[0];
      if( i.above_center() ) return TypeMemPtr.STRPTR.dual();
      if( i instanceof TypeInt && i.is_con() )
        return TypeMemPtr.make(BitsAlias.STRBITS,TypeStr.con(String.valueOf(i.getl()).intern()));
      return TypeMemPtr.STRPTR;
    }
  }


  // flt->(factor flt flt)
  static class Factor extends PrimSyn {
    @Override String name() { return "factor"; }
    public Factor() { super(FLT64(),FLT64()); }
    @Override PrimSyn make() { return new Factor(); }
    @Override Type apply( Type[] flows) {
      Type flt = flows[0];
      if( flt.above_center() ) return TypeFlt.FLT64.dual();
      return TypeFlt.FLT64;
    }
  }

  // ---------------------------------------------------------------------
  // T2 types form a Lattice, with 'unify' same as 'meet'.  T2's form a DAG
  // (cycles if i allow recursive unification) with sharing.  Each Syntax has a
  // T2, and the forest of T2s can share.  Leaves of a T2 can be either a
  // simple concrete base type, or a sharable leaf.  Unify is structural, and
  // where not unifyable the union is replaced with an Error.
  static class T2 implements IntSupplier {
    private static int CNT=1;
    final int _uid=CNT++;
    @Override public int getAsInt() { return _uid; }

    // Structural parts to unify with, or null.
    // If Leaf   , then null and _flow is null.
    // If Base   , then null and _flow is set.
    // If unified, contains the single key ">>" and all other fields are null.
    // If Nil    , contains the single key "?"  and all other fields are null.
    // If Lambda , contains keys "x","y","z" for args or "ret" for return.
    // If Struct , contains keys for the field labels.  No display & not-null.
    NonBlockingHashMap<String,T2> _args;

    // Any/all of Base,Lambda,Struct may appear at once.
    // If more than one appears, then we have a "Cannot unify" error.
    // Nil is NOT allowed to appear with others, but it can fold into all of them.

    // Contains a Bases flow-type, or null if not a Base.
    Type _flow;
    Type _eflow;                // Error flow; incompatible with _flow

    // Contains the set of Lambdas, or null if not a Lambda.
    // If set, then keys x,y,z,ret may appear.
    BitsFun _fidxs;

    // Contains the set of aliased Structs, or null if not a Struct.
    // If set, then keys for field names may appear.
    BitsAlias _aliases;
    // Structs allow more fields.  Not quite the same as TypeStruct._open field.
    boolean _open;

    // Null for no-error, or else a single-T2 error
    String _err = null;

    // Dependent (non-local) tvars to revisit
    Ary<Syntax> _deps;


    private T2(NonBlockingHashMap<String,T2> args) { _args = args; }

    @SuppressWarnings("unchecked")
    T2 copy() {
      // Shallow clone of args
      T2 t = new T2(_args==null ? null : (NonBlockingHashMap<String,T2>)_args.clone());
      t._flow = _flow;
      t._eflow = _eflow;
      t._fidxs = _fidxs;
      t._aliases = _aliases;
      t._open = _open;
      // TODO: stop sharing _deps
      t._deps = _deps;
      t._err = _err;
      return t;
    }

    boolean is_leaf()  { return _args==null && _flow==null && _aliases==null; }
    boolean unified()  { return get(">>")!=null; }
    boolean is_nil()   { return get("?" )!=null; }
    boolean is_base()  { return _flow   != null; } // Can be true with is_base, is_fun, is_struct
    boolean is_fun ()  { return _fidxs  != null; } // Can be true with is_base, is_fun, is_struct
    boolean is_struct(){ return _aliases!= null; } // Can be true with is_base, is_fun, is_struct
    boolean is_open()  { return _open; }           // Struct-specific
    boolean is_err()   { return _err!=null || is_err2(); }
    boolean is_err2()  { return
        (_flow   ==null ? 0 : 1) +                 // Any 2 or more set of _flow,_fidxs,_aliases
        (_eflow  ==null ? 0 : 1) +                 // Any 2 or more set of _flow,_fidxs,_aliases
        (_fidxs  ==null ? 0 : 1) +
        (_aliases==null ? 0 : 1) >= 2;
    }
    int size() { return _args==null ? 0 : _args.size(); }
    // A faster debug not-UF lookup
    private T2 get( String key) { return _args==null ? null : _args.get(key); }
    // U-F find on the args collection
    T2 arg( String key) {
      T2 u = get(key);
      if( u==null ) return null;
      T2 uu = u.find();
      if( u!=uu ) _args.put(key,uu);
      return uu;
    }

    // Constructor factories.
    static T2 make_leaf() { return new T2(null); }
    static T2 make_nil (T2 leaf) { return new T2(new NonBlockingHashMap<>(){{put("?",leaf);}}); }
    static T2 make_base(Type flow) {
      assert !(flow instanceof TypeStruct) && !(flow instanceof TypeFunPtr);
      T2 t2 = new T2(null);
      t2._flow=flow;
      return t2;
    }
    static T2 make_fun( BitsFun fidxs, T2... t2s ) {
      NonBlockingHashMap<String,T2> args = new NonBlockingHashMap<>();
      for( int i=0; i<t2s.length-1; i++ )
        args.put(Lambda.ARGNAMES[i], t2s[i]);
      args.put("ret",t2s[t2s.length-1]);
      T2 t2 = new T2(args);
      t2._fidxs = fidxs;
      return t2;
    }
    // A struct with fields
    static T2 make_open_struct( BitsAlias aliases, String[] ids, T2[] flds ) {
      NonBlockingHashMap<String,T2> args = ids==null ? null : new NonBlockingHashMap<>();
      if( ids!=null )
        for( int i=0; i<ids.length; i++ )
          args.put(ids[i],flds[i]);
      T2 t2 = new T2(args);
      t2._aliases = aliases;
      t2._open = false;
      return t2;
    }

    T2 debug_find() {// Find, without the roll-up
      if( !unified() ) return this; // Shortcut
      if( _args==null ) return this;
      T2 u = _args.get(">>");
      if( !u.unified() ) return u;  // Shortcut
      // U-F search, no fixup
      while( u.unified() ) u = u._args.get(">>");
      return u;
    }

    T2 find() {
      T2 u = _find0();
      return u.is_nil() ? u._find_nil() : u;
    }
    // U-F find
    private T2 _find0() {
      T2 u = debug_find();
      if( u==this ) return u;
      if( u==_args.get(">>") ) return u;
      // UF fixup
      T2 v = this, v2;
      while( (v2=v._args.get(">>"))!=u ) { v._args.put(">>",u); v = v2; }
      return u;
    }
    // Nilable fixup.  nil-of-leaf is OK.  nil-of-anything-else folds into a
    // nilable version of the anything-else.
    private T2 _find_nil() {
      T2 n = arg("?");
      if( n.is_leaf() ) return this;
      _args.remove("?");  // No longer have the "?" key, not a nilable anymore
      // Nested nilable-and-not-leaf, need to fixup the nilable
      if( n.is_base() ) {
        _flow = n._flow.meet(Type.NIL);
        if( n._eflow!=null ) _eflow = n._eflow.meet(Type.NIL);
      }
      if( n.is_fun() ) {
        throw unimpl();
      }
      if( n.is_struct() ) {
        if( n._args!=null )     // Shallow copy fields
          for( String key : n._args.keySet() )
            _args.put(key,n.get(key));
        _aliases = n._aliases.meet_nil();
        _open = n._open;
      }
      if( n.is_nil() ) {
        _args.put("?",n.arg("?"));
      }
      if( _args.size()==0 ) _args=null;
      n.merge_deps(this);
      return this;
    }

    private long dbl_uid(T2 t) { return dbl_uid(t._uid); }
    private long dbl_uid(long uid) { return ((long)_uid<<32)|uid; }

    // True if any portion allows for nil
    boolean has_nil() {
      if(  _flow  !=null &&  _flow.must_nil() ) return true;
      if( _eflow  !=null && _eflow.must_nil() ) return true;
      if( _fidxs  !=null && _fidxs   .test(0) ) return true;
      if( _aliases!=null && _aliases .test(0) ) return true;
      return false;
    }

    // Strip off nil
    T2 strip_nil() {
      if(    _flow!=null )    _flow =   _flow.join(Type.NSCALR);
      if(   _eflow!=null )   _eflow =  _eflow.join(Type.NSCALR);
      if(   _fidxs!=null )   _fidxs =  _fidxs.clear(0);
      if( _aliases!=null ) _aliases =_aliases.clear(0);
      return this;
    }
    // Add nil
    void add_nil() {
      if(    _flow!=null )    _flow =   _flow.meet(Type.NIL);
      if(   _eflow!=null )   _eflow =  _eflow.meet(Type.NIL);
      if(   _fidxs!=null )   _fidxs =  _fidxs.set(0);
      if( _aliases!=null ) _aliases =_aliases.set(0);
    }

    // True if 'this isa t2'.  Must be monotonic.
    boolean isa( T2 t2 ) {
      // Leaf can "fall" (unify, expand) into anything.
      // Conversely, nothing can "fall" into a Leaf
      if(    is_leaf() ) return true;
      if( t2.is_leaf() ) return false;
      // Structurally equal
      if( cycle_equals(t2) ) return true;

      // Structural breakdown
      // Check base terms
      if( not_isa(   _flow, t2.   _flow) ) return false;
      if( not_isa(  _eflow, t2.  _eflow) ) return false;
      if( not_isa(  _fidxs, t2.  _fidxs) ) return false;
      if( not_isa(_aliases, t2._aliases) ) return false;
      // Check argument names.  Defensive copy did not go deep, and the
      // lifting does the recursion so we only need to check shallow here.
      if( _args!=null )
        for( String key : _args.keySet() )
          if( t2._args==null || !t2._args.containsKey(key) )
            return false;
      // All parts isa
      return true;
    }
    private static boolean not_isa( Type t0, Type t1 ) {
      return (t0 != null || t1 != null) && (t0 == null || t1 == null || !t0.isa(t1));
    }
    @SuppressWarnings("unchecked")
    private static boolean not_isa( Bits t0, Bits t1 ) {
      return (t0 != null || t1 != null) && (t0 == null || t1 == null || !t0.isa(t1));
    }

    // Varies as unification happens; not suitable for a HashMap/HashSet unless
    // unchanging (e.g. defensive clone)
    @Override public int hashCode() {
      int hash = 0;
      if(    _flow!=null ) hash+=    _flow._hash;
      if(   _eflow!=null ) hash+=   _eflow._hash;
      if(   _fidxs!=null ) hash+=   _fidxs._hash;
      if( _aliases!=null ) hash+= _aliases._hash;
      if( _args!=null )
        for( String key : _args.keySet() )
          hash += key.hashCode();
      return hash;
    }

    // -----------------
    // Recursively build a conservative flow type from an HM type.  The HM
    // is_struct wants to be a TypeMemPtr, but the recursive builder is built
    // around TypeStruct.

    // No function arguments, just function returns.
    static final NonBlockingHashMapLong<Type> ADUPS = new NonBlockingHashMapLong<>();
    Type as_flow() {
      assert ADUPS.isEmpty();
      Type t = _as_flow();
      ADUPS.clear();
      return t;
    }
    Type _as_flow() {
      assert !unified();
      if( is_leaf() ) return Type.SCALAR;
      if( is_base() ) return _flow;
      if( is_nil()  ) return Type.SCALAR;
      if( is_fun()  ) {
        Type tfun = ADUPS.get(_uid);
        if( tfun != null ) return tfun;  // TODO: Returning recursive flow-type functions
        ADUPS.put(_uid, Type.XSCALAR);
        Type rez = arg("ret")._as_flow();
        return TypeFunPtr.make(BitsFun.NZERO,size()-1,Type.ANY,rez);
      }
      if( is_struct() ) {
        TypeStruct tstr = (TypeStruct)ADUPS.get(_uid);
        if( tstr==null ) {
          // Returning a high version of struct
          Type.RECURSIVE_MEET++;
          tstr = TypeStruct.malloc("",is_open(),false).add_fld(TypeFld.NO_DISP);
          if( _args!=null )
            for( String id : _args.keySet() )
              tstr.add_fld(TypeFld.malloc(id));
          tstr.set_hash();
          ADUPS.put(_uid,tstr); // Stop cycles
          if( _args!=null )
            for( String id : _args.keySet() )
              tstr.get(id).setX(arg(id)._as_flow()); // Recursive
          if( --Type.RECURSIVE_MEET == 0 )
            // Shrink / remove cycle dups.  Might make new (smaller)
            // TypeStructs, so keep RECURSIVE_MEET enabled.
            tstr = tstr.install();
        }
        return TypeMemPtr.make(_aliases,tstr);
      }

      throw unimpl();
    }


    // -----------------
    // U-F union; this becomes that; returns 'that'.
    // No change if only testing, and reports progress.
    boolean union(T2 that, Work<Syntax> work) {
      assert !unified() && !that.unified(); // Cannot union twice
      if( this==that ) return false;
      if( work==null ) return true; // Report progress without changing

      // Merge all the hard bits
      unify_base(that, work);
      if( _fidxs !=null ) that._fidxs = that._fidxs==null ? _fidxs : that._fidxs.meet(_fidxs);
      if( _aliases!=null ) {
        if( that._aliases==null ) { that._aliases = _aliases; that._open  = _open; }
        else {  that._aliases = that._aliases.meet(_aliases); that._open &= _open; }
      }
      if( _args!=null ) {
        if( that._args==null ) { that._args = _args; _args=null; }
        else that._args.putAll(_args);
      }
      if( _err!=null && that._err==null ) that._err = _err;
      else if( _err!=null && !_err.equals(that._err) )
        throw unimpl();         // TODO: Combine single errors

      // Work all the deps
      that.add_deps_work(work);
      this.add_deps_work(work);      // Any progress, revisit deps
      // Hard union this into that, no more testing.
      return _union(that);
    }

    // Hard unify this into that, no testing for progress.
    private boolean _union( T2 that ) {
      assert !unified() && !that.unified(); // Cannot union twice
      // Work<Syntax>: put updates on the worklist for revisiting
      merge_deps(that);    // Merge update lists, for future unions
      // Kill extra information, to prevent accidentally using it
      _args = new NonBlockingHashMap<>() {{put(">>", that);}};
      _flow = _eflow = null;
      _fidxs= null;
      _aliases=null;
      _open = false;
      _deps = null;
      _err  = null;
      assert unified();
      return true;
    }

    // Unify this._flow into that._flow.  Flow is limited to only one of
    // {int,flt,ptr} and a 2nd unrelated flow type is kept as an error in
    // that._eflow.  Basically a pick the max 2 of 4 values, and each value is
    // range 0-3.  Returns progress.
    boolean unify_base(T2 that, Work<Syntax> work) {
      Type sf = _flow , hf = that._flow ;     // Flow of self and that.
      if( sf==null && hf==null ) return false;// Fast cutout
      Type se = _eflow, he = that._eflow;     // Error flow of self and that.
      Type of = that._flow, oe = that._eflow; // Old versions, to check for progress
      int cmp =  _fpriority(sf) - _fpriority(hf);
      if( cmp == 0 ) { that._flow = sf.meet(hf); sf = se; hf = he; } // Tied; meet; advance both
      if( cmp  > 0 ) { that._flow = sf;          sf = se;          } // Pick winner, advance
      if( cmp  < 0 ) {                           hf = he;          } // Pick winner, advance
      if( !(sf==null && hf==null) ) {                                // If there is an error flow
        int cmp2 =  _fpriority(sf) - _fpriority(hf);
        if( cmp2 == 0 ) that._eflow = sf.meet(hf);
        if( cmp2  > 0 ) that._eflow = sf;
        if( cmp2  < 0 ) that._eflow = hf;
      }
      boolean progress = of!=that._flow || oe!=that._eflow; // Progress check
      if( work==null && progress ) { that._flow=of; that._eflow=oe; } // Unwind if just testing
      return progress;
    }
    // Sort flow types; int >> flt >> ptr >> null
    private int _fpriority( Type t0 ) {
      if( t0 instanceof TypeInt ) return 3;
      if( t0 instanceof TypeFlt ) return 2;
      if( t0 instanceof TypeMemPtr ) return 1;
      assert t0==null;
      return 0;
    }


    // U-F union; that is nilable and this becomes that.
    // No change if only testing, and reports progress.
    boolean unify_nil(T2 that, Work<Syntax> work) {
      assert !is_nil() && that.is_nil();
      if( work==null ) return true; // Will make progress;
      T2 leaf = that.arg("?");  assert leaf.is_leaf();
      leaf.add_deps_work(work);
      T2 copy = copy().strip_nil();
      return leaf.union(copy,work) | _union(that);
    }
    // U-F union; that is nilable and a fresh copy of this becomes that.
    // No change if only testing, and reports progress.
    boolean unify_nil(T2 that, Work<Syntax> work, VStack nongen) {
      assert !is_nil() && that.is_nil();
      if( work==null ) return true; // Will make progress;
      T2 leaf = that.arg("?");  assert leaf.is_leaf();
      // A shallow copy and fresh-unify fails if 'this' is cyclic, because the
      // shallow copy peels one part of the loop.
      //T2 copy = copy().strip_nil();
      //copy._fresh_unify(leaf,nongen,work);
      T2 copy = _fresh(nongen).strip_nil();
      copy._unify(leaf,work);
      return vput(that,true);
    }

    // -----------------
    // Structural unification.
    // Returns false if no-change, true for change.
    // If work is null, does not actually change anything, just reports progress.
    // If work and change, unifies 'this' into 'that' (changing both), and
    // updates the worklist.
    static private final HashMap<Long,T2> DUPS = new HashMap<>();
    boolean unify( T2 that, Work<Syntax> work ) {
      if( this==that ) return false;
      assert VARS.isEmpty() && DUPS.isEmpty();
      boolean progress = _unify(that,work);
      VARS.clear();  DUPS.clear();
      return progress;
    }

    // Structural unification, 'this' into 'that'.  No change if just testing
    // (work is null) and returns a progress flag.  If updating, both 'this'
    // and 'that' are the same afterwards.
    private boolean _unify(T2 that, Work<Syntax> work) {
      assert !unified() && !that.unified();
      if( this==that ) return false;

      // Any leaf immediately unifies with any non-leaf
      if( this.is_leaf() && that.is_leaf() && _uid<that._uid )
        return that.union(this,work); // Two leafs sort by _uid
      if( this.is_leaf() ) return this.union(that,work);
      if( that.is_leaf() ) return that.union(this,work);

      // Two bases unify by smaller uid
      if( is_base() && that.is_base() )
        return _uid<that._uid ? that.union(this,work) : this.union(that,work);

      // Special case for nilable union something
      if( this.is_nil() && !that.is_nil() ) return that.unify_nil(this,work);
      if( that.is_nil() && !this.is_nil() ) return this.unify_nil(that,work);

      // Cycle check
      long luid = dbl_uid(that);    // long-unique-id formed from this and that
      T2 rez = DUPS.get(luid);
      assert rez==null || rez==that;
      if( rez!=null ) return false; // Been there, done that
      DUPS.put(luid,that);          // Close cycles

      if( work==null ) return true; // Here we definitely make progress; bail out early if just testing

      // Structural recursion unification.
      if( is_struct() && that.is_struct() )  unify_flds(this,that,work,false);
      else if( is_fun() && that.is_fun()  )  unify_flds(this,that,work,false);
      return find().union(that.find(),work);
    }

    // Structural recursion unification.  Called nested, and called by NotNil
    // at the top-level directly.
    static boolean unify_flds(T2 thsi, T2 that, Work<Syntax> work, boolean top_level) {
      if( thsi._args==that._args ) return false;  // Already equal (and probably both nil)
      boolean progress = false;
      for( String key : thsi._args.keySet() ) {
        T2 fthis = thsi.arg(key); // Field of this
        T2 fthat = that.arg(key); // Field of that
        if( fthat==null ) {       // Missing field in that
          progress = true;
          if( that.is_open() ) that.add_fld(key,fthis,work); // Add to RHS
          else                 thsi.del_fld(key, work); // Remove from LHS
        } else progress |= top_level         // Matching fields unify directly
                 ? fthis. unify(fthat,work)  // Top-level requires some setup
                 : fthis._unify(fthat,work); // Recursive skips the setup
        // Progress may require another find()
        thsi=thsi.find();
        that=that.find();
      }
      // Fields on the RHS are aligned with the LHS also
      if( that._args!=null )
        for( String key : that._args.keySet() )
          if( thsi.arg(key)==null ) { // Missing field in this
            progress = true;
            if( thsi.is_open() )  thsi.add_fld(key,that.arg(key),work); // Add to LHS
            else                  that.del_fld(key, work);              // Drop from RHS
          }

      if( that.debug_find() != that ) throw unimpl(); // Missing a find
      return progress;
    }

    // Insert a new field
    private boolean add_fld(String id, T2 fld, Work<Syntax> work) {
      if( _args==null ) _args = new NonBlockingHashMap<>();
      fld.push_update(_deps);
      _args.put(id,fld);
      add_deps_work(work);
      return true;
    }
    // Delete a field
    private boolean del_fld( String id, Work<Syntax> work) {
      add_deps_work(work);
      _args.remove(id);
      if( _args.size()==0 ) _args=null;
      return true;
    }

    // -----------------
    // Make a (lazy) fresh copy of 'this' and unify it with 'that'.  This is
    // the same as calling 'fresh' then 'unify', without the clone of 'this'.
    // Returns progress.
    // If work is null, we are testing only and make no changes.
    static private final HashMap<T2,T2> VARS = new HashMap<>();
    // Outer version, wraps a VARS check around other work
    boolean fresh_unify(T2 that, VStack nongen, Work<Syntax> work) {
      assert VARS.isEmpty() && DUPS.isEmpty();
      int old = CNT;
      boolean progress = _fresh_unify(that,nongen,work);
      VARS.clear();  DUPS.clear();
      if( work==null && old!=CNT )
        throw unimpl("busted, made T2s but just testing");
      return progress;
    }

    // Inner version, self-recursive and uses VARS and DUPS for cycles.
    @SuppressWarnings("unchecked")
    private boolean _fresh_unify(T2 that, VStack nongen, Work<Syntax> work) {
      assert !unified() && !that.unified();
      // Check for cycles
      T2 prior = VARS.get(this);
      if( prior!=null )         // Been there, done that
        return prior.find()._unify(that,work);  // Also, 'prior' needs unification with 'that'
      // Check for equals
      if( cycle_equals(that) ) return vput(that,false);

      // In the non-generative set, so do a hard unify, not a fresh-unify.
      if( nongen_in(nongen) ) return vput(that,_unify(that,work)); // Famous 'occurs-check', switch to the normal unify

      // LHS leaf, RHS is unchanged but goes in the VARS
      if( this.is_leaf() ) return vput(that,false);
      if( that.is_leaf() )  // RHS is a tvar; union with a deep copy of LHS
        return work==null || vput(that,that.union(_fresh(nongen),work));

      // Special handling for nilable
      boolean progress = false;
      if( this.is_nil() && !that.is_nil() ) {
        Type mt  = that. _flow==null ? null : that. _flow.meet(Type.NIL);
        if(  mt!=that. _flow ) { if( work==null ) return true; progress = true; that._flow   =mt; }
        Type emt = that._eflow==null ? null : that._eflow.meet_nil(Type.XNIL);
        if( emt!=that._eflow ) { if( work==null ) return true; progress = true; that._eflow  =emt;}
        BitsFun bf = that._fidxs==null ? null : that._fidxs.set(0);
        if( bf!=that._fidxs  ) { if( work==null ) return true; progress = true; that._fidxs  =bf; }
        BitsAlias bs = that._aliases==null ? null : that._aliases.set(0);
        if( bs!=that._aliases) { if( work==null ) return true; progress = true; that._aliases=bs; }
        if( progress ) that.add_deps_work(work);
        return vput(that,progress);
      }
      // That is nilable and this is not
      if( that.is_nil() && !this.is_nil() )
        return unify_nil(that,work,nongen);

      // Progress on the parts
      if( _flow!=null ) progress = unify_base(that, work);
      if( _fidxs!=null ) {
        BitsFun mt = that._fidxs==null ? _fidxs : _fidxs.meet(that._fidxs);
        if( mt!=that._fidxs ) {
          if( work==null ) return true;
          progress = true;
          if( !that.is_fun() && that._args==null )
            that._args = (NonBlockingHashMap<String,T2>)_args.clone(); // Error case; bring over the function args
          that._fidxs=mt;
        }
      }
      if( _aliases!=null ) {
        BitsAlias mt = that._aliases==null ? _aliases : _aliases.meet(that._aliases);
        if( mt!=that._aliases ) {
          if( work==null ) return true;
          progress = true;
          if( !that.is_struct() && that._args==null )
            that._args = (NonBlockingHashMap<String,T2>)_args.clone(); // Error case; bring over the function args
          that._aliases=mt;
        }
      }
      if( _err!=null && !_err.equals(that._err) ) {
        if( that._err!=null ) throw unimpl(); // TODO: Combine single error messages
        else {
           if( work==null ) return true;
           progress = true;
           that._err = _err;
        }
      }

      // Both same (probably both nil)
      vput(that,progress);      // Early set, to stop cycles
      if( _args==that._args ) return progress;

      // Structural recursion unification, lazy on LHS
      boolean missing = size()!= that.size();
      for( String key : _args.keySet() ) {
        T2 lhs = this.arg(key);
        T2 rhs = that.arg(key);
        if( rhs==null ) {         // No RHS to unify against
          missing = true;         // Might be missing RHS
          if( is_open() || that.is_open() || lhs.is_err() ) {
            if( work==null ) return true; // Will definitely make progress
            T2 nrhs = lhs._fresh(nongen); // New RHS value
            if( !that.is_open() )
              nrhs._err = "Missing field "+key;   // TODO: Stamp "missing field" into nrhs
            progress |= that.add_fld(key,nrhs,work);
          } // Else neither side is open, field is not needed in RHS
        } else {
          progress |= lhs._fresh_unify(rhs,nongen,work);
        }
        that=that.find();
        if( progress && work==null ) return true;
      }
      // Fields in RHS and not the LHS are also merged; if the LHS is open we'd
      // just copy the missing fields into it, then unify the structs (shortcut:
      // just skip the copy).  If the LHS is closed, then the extra RHS fields
      // are removed.
      if( missing && is_struct() && !is_open() && that._args!=null )
        for( String id : that._args.keySet() ) // For all fields in RHS
          if( arg(id)==null ) {                // Missing in LHS
            if( work == null ) return true;    // Will definitely make progress
            progress |= that.del_fld(id,work);
          }
      if( _aliases!=null && that._open && !_open) { progress = true; that._open = false; }
      if( progress ) that.add_deps_work(work);
      return progress;
    }
    private boolean vput(T2 that, boolean progress) { VARS.put(this,that); return progress; }

    // Return a fresh copy of 'this'
    T2 fresh() {
      assert VARS.isEmpty();
      T2 rez = _fresh(null);
      VARS.clear();
      return rez;
    }
    private T2 _fresh(VStack nongen) {
      assert !unified();
      T2 rez = VARS.get(this);
      if( rez!=null ) return rez; // Been there, done that
      // Unlike the original algorithm, to handle cycles here we stop making a
      // copy if it appears at this level in the nongen set.  Otherwise, we'd
      // clone it down to the leaves - and keep all the nongen leaves.
      // Stopping here preserves the cyclic structure instead of unrolling it.
      if( nongen_in(nongen) ) {
        VARS.put(this,this);
        return this;
      }
      // Structure is deep-replicated
      T2 t = copy();
      if( is_leaf() ) t._deps=null;
      VARS.put(this,t);         // Stop cyclic structure looping
      if( _args!=null )
        for( String key : _args.keySet() )
          t._args.put(key, arg(key)._fresh(nongen));
      return t;
    }

    // -----------------
    private static final VBitSet ODUPS = new VBitSet();

    boolean _occurs_in_type(T2 x) {
      assert !unified() && !x.unified();
      if( x==this ) return true;
      if( ODUPS.tset(x._uid) ) return false; // Been there, done that
      if( x._args!=null )
        for( String key : x._args.keySet() )
          if( _occurs_in_type(x.arg(key)) )
            return true;
      return false;
    }

    boolean nongen_in(VStack vs) {
      if( vs==null ) return false;
      ODUPS.clear();
      for( T2 t2 : vs )
        if( _occurs_in_type(t2.find()) )
          return true;
      return false;
    }

    // -----------------
    // Test for structural equivalence, including cycles
    static private final HashMap<T2,T2> CDUPS = new HashMap<>();
    boolean cycle_equals(T2 t) {
      assert CDUPS.isEmpty();
      boolean rez = _cycle_equals(t);
      CDUPS.clear();
      return rez;
    }
    boolean _cycle_equals(T2 t) {
      assert !unified() && !t.unified();
      if( this==t ) return true;
      if( _flow   !=t._flow    ) return false; // Base-cases have to be completely identical
      if( _eflow  !=t._eflow   ) return false;
      if( _fidxs  !=t._fidxs   ) return false; // Base-cases have to be completely identical
      if( _aliases!=t._aliases ) return false; // Base-cases have to be completely identical
      if( _err!=null && !_err.equals(t._err) ) return false; // Base-cases have to be completely identical
      if( is_leaf() ) return false;               // Two leaves must be the same leaf, already checked for above
      if( size() != t.size() ) return false;      // Mismatched sizes
      if( _args==t._args ) return true;           // Same arrays (generally both null)
      // Cycles stall the equal/unequal decision until we see a difference.
      T2 tc = CDUPS.get(this);
      if( tc!=null )  return tc==t; // Cycle check; true if both cycling the same
      CDUPS.put(this,t);
      for( String key : _args.keySet() ) {
        T2 arg = t.arg(key);
        if( arg==null || !arg(key)._cycle_equals(arg) )
          return false;
      }
      return true;
    }

    // -----------------
    static private final HashMap<T2,Type> T2MAP = new HashMap<>();
    static private final NonBlockingHashMapLong<TypeStruct> WDUPS = new NonBlockingHashMapLong<>();
    static private final BitSet WBS = new BitSet();

    // Lift the flow Type of an Apply, according to its inputs.  This is to
    // help preserve flow precision across polymorphic calls, where the input
    // flow types all meet - but HM understands how the T2s split back apart
    // after the Apply.  During this work, every T2 is mapped one-to-one to a
    // flow Type, and the mapping is made recursively.

    // Walk a T2 and a matching flow-type, and build a map from T2 to flow-types.
    // Stop if either side loses corresponding structure.  This operation must be
    // monotonic because the result is JOINd with GCP types.
    Type walk_types_in(Type t) {     //noinspection UnusedReturnValue
      long duid = dbl_uid(t._uid);
      if( WDUPS.putIfAbsent(duid,TypeStruct.ALLSTRUCT)!=null ) return t;
      assert !unified();
      // Free variables keep the input flow type.
      // Bases can (sorta) act like a leaf: they can keep their polymorphic "shape" and induce it on the result
      if( is_leaf() || is_base() )
        { T2MAP.merge(this, t, HM_FREEZE ? Type::meet : Type::join); return t; }
      // Nilable
      if( is_nil() )
        return arg("?").walk_types_in(t.join(Type.NSCALR));
      if( is_fun() ) {          // Walk returns not arguments
        T2 t2ret = arg("ret");
        Type fret = t instanceof TypeFunPtr ? ((TypeFunPtr)t)._ret : t.oob(Type.SCALAR);
        if( fret == Type.ANY || fret == Type.ALL )
          throw unimpl();       // Should fix to Scalar before starting
        return t2ret.walk_types_in(fret);
      }

      if( is_struct() ) {       // Walk all fields
        if( _args!=null )
          for( String id : _args.keySet() )
            arg(id).walk_types_in(at_fld(t,id));
        return t;
      }

      throw unimpl();
    }

    private static Type at_fld(Type t, String id) {
      if( !(t instanceof TypeMemPtr) ) return t.oob(Type.SCALAR);
      TypeMemPtr tmp = (TypeMemPtr)t;
      if( !(tmp._obj instanceof TypeStruct) ) return t.oob(Type.SCALAR);
      TypeStruct ts = (TypeStruct)tmp._obj;
      TypeFld fld = ts.get(id);
      if( fld==null ) return ts.oob(Type.SCALAR);
      return fld._t;
    }

    // Walk an Apply output flow type, and attempt to replace parts of it with
    // stronger flow types from the matching input types.
    Type walk_types_out( Type t, Apply apply ) {
      assert !unified();
      if( t==Type.XSCALAR || t==Type.XNSCALR ) return t; // Cannot lift anymore

      if( is_err() ) return record_lift(apply,t,Type.SCALAR); // Do not attempt lift

      if( is_leaf() ) {
        Type rez = _lift_leaf(apply,t,true);// Pre-freeze, match leaf or base
        if( rez!=null ) return rez;
        // Post-freeze, match direct hit only
        Type xt = T2MAP.get(this);
        if( xt==null || xt==t ) return t; // No mapping, no lift
        push_update(apply);    // Apply depends on this leaf
        return record_lift(apply,t,xt);
      }

      if( is_base() ) {
        Type rez = _lift_leaf(apply,t,false);
        return rez==null ? t : rez;
      }

      if( is_nil() ) { // The wrapped leaf gets lifted, then nil is added
        Type tnil = arg("?").walk_types_out(t.remove_nil(),apply);
        return record_lift(apply,t,tnil.meet(Type.NIL));
      }

      if( is_fun() ) {
        if( t==Type.SCALAR || t==Type.ALL ) t = TypeFunPtr.GENERIC_FUNPTR;
        if( t instanceof TypeFunPtr ) {
          TypeFunPtr tfp = (TypeFunPtr)t;
          for( int fidx : tfp._fidxs ) if( WBS.get(fidx) ) return t; // Recursive function return, no more lifting
          for( int fidx : tfp._fidxs ) WBS.set(fidx);                // Guard against recursive functions
          Type tret = tfp._ret;
          Type trlift = arg("ret").walk_types_out(tret, apply);
          Type rez = TypeFunPtr.makex( tfp._fidxs,tfp.nargs(),tfp._dsp,trlift);
          for( int fidx : tfp._fidxs ) WBS.clear(fidx); // Clear fidxs
          return record_lift(apply,t,rez);
        }
        // TODO: Flow Scalar is OK, will lift to a TFP->Scalar
        throw unimpl();
      }

      if( is_struct() ) {
        if( !(t instanceof TypeMemPtr ) ) // Flow will not lift to a TMP->Struct?
          return record_lift(apply,t,t.must_nil() ? Type.SCALAR : Type.NSCALR);
        TypeMemPtr tmp = (TypeMemPtr)t;
        TypeStruct ts0 = (TypeStruct)tmp._obj;
        // Can be made to work above_center, but no sensible lifting so don't bother
        if( ts0.above_center() )  return record_lift(apply,t,Type.SCALAR);
        TypeStruct ts = WDUPS.get(_uid);
        if( ts != null ) return t; // Recursive, stop cycles
        Type.RECURSIVE_MEET++;
        ts = TypeStruct.malloc("",false,false);

        // Add fields.  Common to both are easy, and will be walked (recursive,
        // cyclic).  Solo fields in GCP are kept, and lifted "as if" an HM
        // field will appear with max lifting.  Solo fields in HM are ignored,
        // as GCP will appear with them later.
        if( _args!=null )
          for( String id : _args.keySet() ) // Forall fields in HM
            if( ts0.get(id)!=null )         // and in GCP
              ts.add_fld( TypeFld.malloc(id,null,Access.Final,TypeFld.oBot) );
        if( is_open() && !HM_FREEZE )     // If can add fields to HM
          for( TypeFld fld : ts0.flds() ) // Forall fields in GCP
            if( get(fld._fld)==null )     // Solo in GCP
              ts.add_fld( fld.copy() );   // Add a copy
        if( ts.get("^")==null ) ts.add_fld(TypeFld.NO_DISP);
        WDUPS.put(_uid,ts.set_hash());    // Stop cycles

        // Walk fields common to both, setting (cyclic, recursive) the lifted type
        if( _args!=null )
          for( String id : _args.keySet() ) { // Forall fields in HM
            TypeFld fld0 = ts0.get(id);
            if( fld0 !=null )                 // and in GCP
              // Recursively walk and lift into the new ts struct
              ts.get(id).setX( arg(id).walk_types_out(fld0._t,apply), fld0._order);
          }
        // Now do the other side.  Missing on HM side might later appear and
        // lift to anything.
        if( is_open() && !HM_FREEZE )       // If can add fields to HM
          for( TypeFld fld : ts0.flds() )   // Forall fields in GCP
            if( get(fld._fld)==null && !Util.eq(fld._fld,"^") ) // Solo in GCP
              ts.get(fld._fld).setX( fld._t, fld._order );
        // Close off the recursion
        if( --Type.RECURSIVE_MEET == 0 )
          ts = ts.install();
        return record_lift(apply,t,tmp.make_from(ts));
      }

      throw unimpl();           // Handled all cases
    }

    private Type _lift_leaf(Apply apply, Type t, boolean base) {
      if( HM_FREEZE ) return null; // Post-freeze, be exact
      Type jt2 = Type.SCALAR;
      for( T2 t2 : T2.T2MAP.keySet() )
        if( t2.is_leaf() || (base && t2.is_base()) )
          jt2 = jt2.join(T2.T2MAP.get(t2));
      if( jt2==Type.SCALAR || t.isa(jt2) ) return jt2; // No lift
      // Using all these leafs to lift, so depend on them still being leafs.
      for( T2 t2 : T2.T2MAP.keySet() )
        if( t2.is_leaf() || (base && t2.is_base()) )
          t2.push_update(apply);
      return record_lift(apply,t,jt2);
    }

    // Walking the T2:self and output flow type:t in parallel.  If I find an
    // output leaf that has a corresponding input leaf I can use it (or jt) to
    // lift the output flow to the matching input flow.  Record all these,
    // and verify monotonicity.
    static class LiftSet {
      T2 _t2;  Type _t, _rez;  Apply _apply;
      private static final HashSet<LiftSet> LIFTS = new HashSet<>();
      private static LiftSet FREE;
      private void set( Apply apply, T2 t2, Type t, Type rez) { _apply=apply; _t2=t2; _t=t; _rez=rez; }
      static  void add( Apply apply, T2 t2, Type t, Type rez ) {
        if( FREE==null ) FREE=new LiftSet();
        FREE.set(apply,t2,t,rez);
        // Fast dup check
        if( LIFTS.contains(FREE) ) return;
        for( LiftSet lf : LIFTS )
          assert !lf.isa(FREE) || lf._rez.isa(rez);
        FREE._t2 = t2.copy(); // Defensive copy
        LIFTS.add(FREE);
        FREE=null;
      }
      // IsA relationship.
      private boolean isa( LiftSet lf ) {
        return _apply==lf._apply && _t.isa(lf._t) && _t2.isa(lf._t2);
      }
      @Override public boolean equals( Object o ) {
        if( !(o instanceof LiftSet) ) return false;
        LiftSet lf = (LiftSet)o;
        if( _apply != lf._apply ) return false;
        if( _t2.is_leaf() && lf._t2.is_leaf() ) return true;
        return _t2.cycle_equals(lf._t2);
      }
      @Override public int hashCode() { return _t2.hashCode() + _t._hash + _apply._uid; }
      @Override public String toString() { return "(A#"+_apply._uid+","+_t2+","+_t+")->"+_rez; }
    }
    // Giant Assert that Apply-Lift is monotonic
    boolean _record_lift(Apply apply, Type t, Type rez) {
      if( t.isa(rez) ) return true; // No lifting going on
      if( !is_leaf() && !is_base() ) return true; // not checking structural types, just the leaves
      LiftSet.add(apply,this,t,rez);
      return true;
    }
    Type record_lift(Apply apply, Type t, Type rez) {
      assert _record_lift(apply,t,rez);
      return rez;
    }

    // -----------------
    // This is a T2 function that is the target of 'fresh', i.e., this function
    // might be fresh-unified with some other function.  Push the application
    // down the function parts; if any changes the fresh-application may make
    // progress.
    static final VBitSet UPDATE_VISIT  = new VBitSet();
    void push_update( Ary<Syntax> as ) { if( as != null ) for( Syntax a : as ) push_update(a); }
    T2 push_update( Syntax a) { assert UPDATE_VISIT.isEmpty(); push_update_impl(a); UPDATE_VISIT.clear(); return this; }
    private void push_update_impl(Syntax a) {
      assert !unified();
      if( UPDATE_VISIT.tset(_uid) ) return;
      if( _deps==null ) _deps = new Ary<>(Syntax.class);
      if( _deps.find(a)==-1 ) _deps.push(a);
      if( _args != null )
        for( T2 t2 : _args.values() )
          t2.debug_find().push_update_impl(a);
    }

    // Recursively add-deps to worklist
    void add_deps_work( Work<Syntax> work ) { assert UPDATE_VISIT.isEmpty(); add_deps_work_impl(work); UPDATE_VISIT.clear(); }
    private void add_deps_work_impl( Work<Syntax> work ) {
      work.addAll(_deps);
      if( _deps!=null )
        for( Syntax syn : _deps )
          if( syn._par instanceof Lambda )
            work.addAll(((Lambda)syn._par)._applys);
      if( UPDATE_VISIT.tset(_uid) ) return;
      if( _args != null )
        for( T2 t2 : _args.values() )
          t2.add_deps_work_impl(work);
    }

    // Merge this._deps into that
    void merge_deps( T2 that ) {
      if( _deps != null )
        that.push_update(_deps);
    }


    // -----------------
    // Glorious Printing

    // Look for dups, in a tree or even a forest (which Syntax.p() does)
    public VBitSet get_dups() { return _get_dups(new VBitSet(),new VBitSet()); }
    public VBitSet _get_dups(VBitSet visit, VBitSet dups) {
      if( visit.tset(_uid) ) {
        dups.set(debug_find()._uid);
      } else {
        if( _args!=null )
          for( T2 t : _args.values() )
            t._get_dups(visit,dups);
      }
      return dups;
    }

    @Override public String toString() { return str(new SB(), new VBitSet(), get_dups(), true ).toString(); }
    public String p() { VCNT=0; VNAMES.clear(); return str(new SB(), new VBitSet(), get_dups(), false ).toString(); }
    private static int VCNT;
    private static final HashMap<T2,String> VNAMES = new HashMap<>();


    // Fancy print for Debuggers - includes explicit U-F re-direction.
    // Does NOT roll-up U-F, has no side-effects.
    SB str(SB sb, VBitSet visit, VBitSet dups, boolean debug) {
      boolean dup = dups.get(_uid);
      if( !debug && unified() ) return find().str(sb,visit,dups,false);
      if( unified() || (is_leaf() && _err==null) ) {
        vname(sb,debug);
        return unified() ? _args.get(">>").str(sb.p(">>"), visit, dups, debug) : sb;
      }

      // Dup printing for all but bases (which are short, just repeat them)
      if( debug || !is_base() || is_err() ) {
        if( dup ) vname(sb,debug);
        if( visit.tset(_uid) && dup ) return sb;
        if( dup ) sb.p(':');
      }

      // Special printing for errors
      if( is_err() ) {
        if( is_err2() ) {
          sb.p("Cannot unify ");
          if( is_fun   () ) str_fun   (sb,visit,dups,debug).p(" and ");
          if( is_base  () ) str_base  (sb)                 .p(" and ");
          if( _eflow!=null) sb.p(_eflow)                   .p(" and ");
          if( is_struct() ) str_struct(sb,visit,dups,debug).p(" and ");
          return sb.unchar(5);
        }
        return sb.p(_err);      // Just a simple error
      }

      if( is_base  () ) return str_base(sb);
      if( is_fun   () ) return str_fun(sb,visit,dups,debug);
      if( is_struct() ) return str_struct(sb,visit,dups,debug);
      if( is_nil   () ) return str0(sb,visit,arg("?"),dups,debug).p('?');

      // Generic structural T2
      sb.p("( ");
      if( _args!=null )
        for( String s : _args.keySet() )
          str0(sb.p(s).p(':'),visit,_args.get(s),dups,debug).p(" ");
      return sb.unchar().p(")");
    }
    static private SB str0(SB sb, VBitSet visit, T2 t, VBitSet dups, boolean debug) { return t==null ? sb.p("_") : t.str(sb,visit,dups,debug); }
    private SB str_base(SB sb) { return sb.p(_flow); }
    private SB str_fun(SB sb, VBitSet visit, VBitSet dups, boolean debug) {
      if( debug ) _fidxs.clear(0).str(sb);
      sb.p("{ ");
      for( String fld : sorted_flds() ) {
        if( fld.charAt(0)!=' ' ) continue; // Ignore struct field
        if( !Util.eq("ret",fld) )
          str0(sb,visit,_args.get(fld),dups,debug).p(' ');
      }
      return str0(sb.p("-> "),visit,_args.get("ret"),dups,debug).p(" }");
    }
    private SB str_struct(SB sb, VBitSet visit, VBitSet dups, boolean debug) {
      if( is_prim() ) return sb.p("@{PRIMS}");
      final boolean is_tup = is_tup(); // Distinguish tuple from struct during printing
      if( debug )
        _aliases.clear(0).str(sb);
      sb.p(is_tup ? "(" : "@{");
      if( _args==null ) sb.p(" ");
      else {
        for( String fld : sorted_flds() ) {
          // Skip fields from functions
          if( fld.charAt(0)==' ' ) continue;
          if( Util.eq(fld,"ret") ) continue;
          // Skip field names in a tuple
          str0(is_tup ? sb.p(' ') : sb.p(' ').p(fld).p(" = "),visit,_args.get(fld),dups,debug).p(is_tup ? ',' : ';');
        }
      }
      if( is_open() ) sb.p(" ...,");
      if( _args!=null && _args.size() > 0 ) sb.unchar();
      sb.p(!is_tup ? "}" : ")");
      if( _aliases.test(0) ) sb.p("?");
      return sb;
    }


    private void vname( SB sb, boolean debug) {
      final boolean vuid = debug && (unified()||is_leaf());
      sb.p(VNAMES.computeIfAbsent(this, (k -> vuid ? ((is_leaf() ? "V" : "X") + k._uid) : ((++VCNT) - 1 + 'A' < 'V' ? ("" + (char) ('A' + VCNT - 1)) : ("V" + VCNT)))));
    }
    private boolean is_tup() { return _args==null || _args.isEmpty() || _args.containsKey("0"); }
    private Collection<String> sorted_flds() { return new TreeMap<>(_args).keySet(); }
    boolean is_prim() { return is_struct() && _args!=null && _args.containsKey("!"); }

    // Debugging tool
    T2 find(int uid) { return _find(uid,new VBitSet()); }
    private T2 _find(int uid, VBitSet visit) {
      if( visit.tset(_uid) ) return null;
      if( _uid==uid ) return this;
      if( _args==null ) return null;
      for( T2 arg : _args.values() )
        if( (arg=arg._find(uid,visit)) != null )
          return arg;
      return null;
    }
    static void reset() { CNT=0; DUPS.clear(); VARS.clear(); ODUPS.clear(); CDUPS.clear(); ADUPS.clear(); UPDATE_VISIT.clear(); LiftSet.LIFTS.clear(); }
  }
}
