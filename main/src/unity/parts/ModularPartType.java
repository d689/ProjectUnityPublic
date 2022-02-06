package unity.parts;

import arc.*;
import arc.graphics.g2d.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.meta.*;
import unity.parts.stat.*;
import unity.parts.stat.AdditiveStat.*;
import unity.util.*;

//like Block, this is a singleton
public class ModularPartType implements Displayable{
    public static IntMap<ModularPartType> partMap = new IntMap<>();

    public static final float partSize = 4;

    public static final int TURRET_TYPE = 1;
    public static final int UNIT_TYPE = 2;
    protected int partType = 0;

    private static int idAcc = 0;
    public final int id = idAcc++;

    public String name;
    public String category;
    public int w=1,h=1;

    //graphics
    public TextureRegion icon;
    /**if true will not have paneling**/
    public boolean open = false;
    public static TextureRegion[] panelling;
    /** texture will/may have three variants for the front middle and back **/
    public TextureRegion[] top;
    public TextureRegion[] outline;
    public boolean hasExtraDecal = false;
    //cost
    public float constructTimeMultiplier = 1; // base time based on item cost
    public ItemStack[] cost = {};
    //module cost..

    //stats
    protected Seq<ModularPartStat> stats = new Seq<>();

    //places it can connect to
    public boolean root = false;


    public ModularPart create(int x, int y){
        return new ModularPart(this,x,y);
    }


    public ModularPartType(String name){
        this.name = name;
        partMap.put(id,this);
    }

    public static void loadStatic(){
        panelling = GraphicUtils.getRegions(Core.atlas.find("unity-panel"), 12, 4,16);
    }

    public void load(){
        ///
        String prefix = "unity-part-"+name;
        icon = Core.atlas.find(prefix+"-icon");
        top = new TextureRegion[]{
            getPartSprite(prefix+"-front"),
            getPartSprite(prefix+"-mid"),
            getPartSprite(prefix+"-back"),
        };
        outline = new TextureRegion[]{
            getPartSprite(prefix+"-front-outline"),
            getPartSprite(prefix+"-mid-outline"),
            getPartSprite(prefix+"-back-outline"),
        };
    }
    public static TextureRegion getPartSprite(String e){
        var f = Core.atlas.find(e);
        if(f == Core.atlas.find("error")){
            f = Core.atlas.find( "unity-part-empty");
        }
        return f;
    }

    public void requirements(String category,ItemStack[] itemcost){
        this.category = category;
        this.cost = itemcost;
    }

    public boolean canBeUsedIn(int type){
        return (type & partType) > 0;
    }

    public void setupPanellingIndex(ModularPart part, ModularPart[][] grid){
        if(part.type!=this){Log.err("part with type "+ part.type.name+" is incorrectly using type "+this.name);return;}
        for(int x = 0;x<w;x++){
            for(int y = 0;y<h;y++){
                part.panelingIndexes[x+y*w]=TilingUtils.getTilingIndex(grid,part.x+x,part.y+y,b -> b!=null && !b.type.open);
            }
        }
    }
    public void drawTop(DrawTransform transform, ModularPart part){
        if(hasExtraDecal)
            transform.drawRect(top[part.front],part.ax*partSize,part.ay*partSize);
    }
    public void draw(DrawTransform transform, ModularPart part){
        transform.drawRect(panelling[part.panelingIndexes[0]],part.ax*partSize,part.ay*partSize);
    }
    public void drawOutline(DrawTransform transform, ModularPart part){
        if(hasExtraDecal)
            transform.drawRect(outline[part.front], part.ax*partSize,part.ay*partSize);
    }

    public static ModularPartType getPartFromId(int id){
        if(partMap.containsKey(id)){
            return partMap.get(id);
        }else{
            Log.info("Part of id "+ id+" not found");
            return partMap.get(0);
        }
    }


    //stats.
    public void appendStats(ModularPartStatMap statmap, ModularPart part, ModularPart[][] grid){
        for(var stat:stats){
            stat.merge(statmap,part);
        }
    }
    public void appendStatsPost(ModularPartStatMap statmap, ModularPart part, ModularPart[][] grid){
        for(var stat:stats){
            stat.mergePost(statmap,part);
        }
    }

    public void health(float amount){
        stats.add(new HealthStat(amount));
    }
    public void mass(float amount){
        stats.add(new MassStat(amount));
    }
    public void producesPower(float amount){
        stats.add(new EngineStat(amount));
    }
    public void usesPower(float amount){
        stats.add(new PowerUsedStat(amount));
    }
    public void healthMul(float amount){
        stats.add(new HealthStat(amount));
    }


    @Override
    public void display(Table table){
        table.table(header->{
            //copied from blocks xd
            header.left();
            header.add(new Image(icon)).size(8 * 4);
            header.labelWrap(() -> Core.bundle.get("part."+name))
            .left().width(190f).padLeft(5);
            header.add().growX();
            header.button("?", Styles.clearPartialt, () -> {
                //Unity.ui.partinfo.show(this);
            }).size(8 * 5).padTop(-5).padRight(-5).right().grow().name("blockinfo");
        });
        table.row();
        table.table(req -> {
            req.top().left();
            req.add("[lightgray]" + Stat.buildCost.localized() + ":[] ").left().top();
            for(ItemStack stack : cost){
                req.add(new ItemDisplay(stack.item, stack.amount, false)).padRight(5);
            }
        }).growX().left().margin(3);
    }
}


