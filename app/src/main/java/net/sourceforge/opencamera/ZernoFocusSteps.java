package net.sourceforge.opencamera;

import java.util.ArrayList;
import java.util.List;

/** Zerno: the focus distances of a Macro series. They are spread evenly between the two ends,
 *  in dioptres (the unit the lens is driven in: the ruler of the manual focus is in dioptres
 *  too). Not in metres: with a clip-on macro lens the metres the camera reports mean nothing,
 *  whereas equal steps of the lens travel are equal steps of the focused plane. (Open Camera
 *  itself bunches the frames towards the near end.)
 *  No Android dependencies, so it can be tested on the JVM.
 */
public final class ZernoFocusSteps {
    private ZernoFocusSteps() {
    }

    /** The focus distances (in dioptres, 0 = infinity) of [count] frames from [first] to [last]
     *  inclusive. The ends are exactly as given; the order is kept (first may be the nearer or
     *  the farther). With one frame, it is [first]; with none, the list is empty.
     */
    public static List<Float> distances(float first, float last, int count) {
        List<Float> list = new ArrayList<>();
        if( count <= 0 )
            return list;
        if( count == 1 ) {
            list.add(first);
            return list;
        }
        for(int i=0;i<count;i++) {
            if( i == 0 )
                list.add(first);
            else if( i == count-1 )
                list.add(last);
            else
                list.add(first + (last - first) * i / (count - 1));
        }
        return list;
    }
}
