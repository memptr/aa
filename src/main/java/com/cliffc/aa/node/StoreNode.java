package com.cliffc.aa.node;

import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.Parse;
import com.cliffc.aa.type.*;
import com.cliffc.aa.tvar.*;
import com.cliffc.aa.util.Util;

// Store a value into a named struct field.  Does it's own nil-check and value
// testing; also checks final field updates.
public class StoreNode extends Node {
  final String _fld;        // Field being updated
  private final byte _fin;  // TypeStruct.ffinal or TypeStruct.frw
  private final Parse _bad;
  public StoreNode( Node mem, Node adr, Node val, byte fin, String fld, Parse bad ) {
    super(OP_STORE,null,mem,adr,val);
    _fld = fld;
    _fin = fin;
    _bad = bad;    // Tests can pass a null, but nobody else does
  }
  private StoreNode( StoreNode st, Node mem, Node adr ) { this(mem,adr,st.rez(),st._fin,st._fld,st._bad); }

  @Override public String xstr() { return "."+_fld+"="; } // Self short name
  String  str() { return xstr(); }   // Inline short name
  @Override public boolean is_mem() { return true; }

  Node mem() { return in(1); }
  Node adr() { return in(2); }
  Node rez() { return in(3); }
  public int find(TypeStruct ts) { return ts.find(_fld); }

  @Override public Node ideal(GVNGCM gvn, int level) {
    Node mem = mem();
    Node adr = adr();
    Type ta = adr.val();
    TypeMemPtr tmp = ta instanceof TypeMemPtr ? (TypeMemPtr)ta : null;

    // If Store is by a New and no other Stores, fold into the New.
    NewObjNode nnn;  int idx;
    if( mem instanceof MrgProjNode &&
        mem.in(0) instanceof NewObjNode && (nnn=(NewObjNode)mem.in(0)) == adr.in(0) &&
        !rez().is_forward_ref() &&
        mem._uses._len==2 &&
        (idx=nnn._ts.find(_fld))!= -1 && nnn._ts.can_update(idx) ) {
      // Update the value, and perhaps the final field
      nnn.update(idx,_fin,rez(),gvn);
      gvn.revalive(mem);
      return mem;               // Store is replaced by using the New directly.
    }

    // If Store is of a memory-writer, and the aliases do not overlap, make parallel with a Join
    if( tmp != null && (tmp._aliases!=BitsAlias.NIL.dual()) &&
        mem.is_mem() && mem.check_solo_mem_writer(this) ) {
      Node head2;
      if( mem instanceof StoreNode ) head2=mem;
      else if( mem instanceof MrgProjNode ) head2=mem;
      else if( mem instanceof MProjNode && mem.in(0) instanceof CallEpiNode ) head2 = mem.in(0).in(0);
      else head2 = null;
      // Check no extra readers/writers at the split point
      if( head2 != null && MemSplitNode.check_split(this,escapees()) )
        return MemSplitNode.insert_split(gvn,this,escapees(),this,mem,head2);
    }

    // If Store is of a MemJoin and it can enter the split region, do so.
    // Requires no other memory *reader* (or writer), as the reader will
    // now see the Store effects as part of the Join.
    if( _keep==0 && tmp != null && mem instanceof MemJoinNode && mem._uses._len==1 ) {
      Node memw = get_mem_writer();
      // Check the address does not have a memory dependence on the Join.
      // TODO: This is super conservative
      if( memw != null && adr instanceof ProjNode && adr.in(0) instanceof NewNode )
        return ((MemJoinNode)mem).add_alias_below_new(gvn,new StoreNode(this,mem,adr),this);
    }

    // Is this Store dead from below?
    if( tmp!=null && _live.ld(tmp)==TypeObj.UNUSED )
      return mem;

    return null;
  }

  // StoreNode needs to return a TypeObj for the Parser.
  @Override public Type value(GVNGCM.Mode opt_mode) {
    Node mem = mem(), adr = adr(), rez = rez();
    Type tmem = mem.val();
    Type tadr = adr.val();
    Type tval = rez.val();  // Value
    if( tmem==Type.ALL || tadr==Type.ALL ) return Type.ALL;

    if( tadr == Type.ALL ) tadr = TypeMemPtr.ISUSED0;
    if( !(tmem instanceof TypeMem   ) ) return tmem.oob(TypeMem.ALLMEM);
    if( !(tadr instanceof TypeMemPtr) ) return tadr.oob(TypeMem.ALLMEM);
    TypeMem    tm  = (TypeMem   )tmem;
    TypeMemPtr tmp = (TypeMemPtr)tadr;
    return tm.update(tmp._aliases,_fin,_fld,tval);
  }
  @Override BitsAlias escapees() {
    Type adr = adr().val();
    if( !(adr instanceof TypeMemPtr) ) return adr.above_center() ? BitsAlias.EMPTY : BitsAlias.FULL;
    return ((TypeMemPtr)adr)._aliases;
  }

  @Override public TypeMem all_live() { return TypeMem.ALLMEM; }
  // Compute the liveness local contribution to def's liveness.  Ignores the
  // incoming memory types, as this is a backwards propagation of demanded
  // memory.
  @Override public TypeMem live_use(GVNGCM.Mode opt_mode, Node def ) {
    if( def==mem() ) return _live; // Pass full liveness along
    if( def==adr() ) return TypeMem.ALIVE; // Basic aliveness
    if( def==rez() ) return TypeMem.ESCAPE;// Value escapes
    throw com.cliffc.aa.AA.unimpl();       // Should not reach here
  }

  @Override public ErrMsg err( boolean fast ) {
    Type tadr = adr().val();
    if( tadr.must_nil() ) return fast ? ErrMsg.FAST : ErrMsg.niladr(_bad,"Struct might be nil when writing",_fld);
    if( !(tadr instanceof TypeMemPtr) )
      return bad("Unknown",fast,null); // Not a pointer nor memory, cannot store a field
    TypeMemPtr ptr = (TypeMemPtr)tadr;
    Type tmem = mem().val();
    if( tmem==Type.ALL ) return bad("Unknown",fast,null);
    if( tmem==Type.ANY ) return null; // No error
    TypeObj objs = tmem instanceof TypeMem
      ? ((TypeMem)tmem).ld(ptr) // General load from memory
      : ((TypeObj)tmem);
    if( !(objs instanceof TypeStruct) ) return bad("No such",fast,objs);
    TypeStruct ts = (TypeStruct)objs;
    int idx = ts.find(_fld);
    if( idx==-1 ) return bad("No such",fast,objs);
    if( !ts.can_update(idx) ) {
      String fstr = TypeStruct.fstring(ts.fmod(idx));
      return bad("Cannot re-assign "+fstr,fast,ts);
    }
    return null;
  }
  private ErrMsg bad( String msg, boolean fast, TypeObj to ) {
    if( fast ) return ErrMsg.FAST;
    boolean is_closure = adr() instanceof ProjNode && adr().in(0) instanceof NewObjNode && ((NewObjNode)adr().in(0))._is_closure;
    return ErrMsg.field(_bad,msg,_fld,is_closure,to);
  }
  @Override public int hashCode() { return super.hashCode()+_fld.hashCode()+_fin; }
  // Stores are can be CSE/equal, and we might force a partial execution to
  // become a total execution (require a store on some path it didn't happen).
  // This can be undone later with splitting.
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !(o instanceof StoreNode) || !super.equals(o) ) return false;
    StoreNode st = (StoreNode)o;
    return _fin==st._fin && Util.eq(_fld,st._fld);
  }

  @Override public boolean unify( GVNGCM gvn, boolean test ) {
    boolean progress=false;
    // Self should always should be a TMem
    TVar tvar = tvar();
    if( !(tvar instanceof TMem) ) {
      if( tvar instanceof TVDead ) return false; // Not gonna be a TMem
      progress=true;            // Would make progress
      if( !test ) tvar = tvar.unify(new TMem(this));
    }
    // Input should be a TMem also
    Node mem = mem();
    TVar tmem = mem.tvar();
    if( !(tmem instanceof TMem) ) {
      if( tmem instanceof TVDead ) return false; // Not gonna be a TMem
      progress=true;            // Would make progress
      if( !test ) tmem = tmem.unify(new TMem(mem));
    }
    // Unify incoming & outgoing memory, modulo the stored value
    if( !test && progress ) {
      // Address needs to name the aliases
      Type tadr = adr().val();
      if( !(tadr instanceof TypeMemPtr) )
        tadr = tadr.oob(TypeMemPtr.ISUSED);
      TypeMemPtr tmp = (TypeMemPtr)tadr;
      // Unify all memory
      //tvar.unify(tmem);
      // Also unify at the given alias & field name
      //((TMem)tvar).unify_alias((TMem)tmem,(TypeMemPtr)tadr,val().tvar());
    }
    return progress;
  }

}
