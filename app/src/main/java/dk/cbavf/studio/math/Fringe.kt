package dk.cbavf.studio.math

import kotlin.math.*

object Fringe {
    /**
     * Calculates the beat wave vector [kbx, kby] for two line gratings.
     *
     * The beat vector is computed as kb = k1 - k2 where:
     * k1 = (1/p1, 0) and k2 = (cos(theta)/p2, sin(theta)/p2)
     *
     * @param p1 the pitch of grating 1
     * @param p2 the pitch of grating 2
     * @param theta the rotation angle of grating 2 in radians
     * @return the beat vector as [kbx, kby]
     */
    fun beatVector(p1: Double, p2: Double, theta: Double): DoubleArray {
        val kbx = 1.0 / p1 - cos(theta) / p2
        val kby = -sin(theta) / p2
        return doubleArrayOf(kbx, kby)
    }

    /**
     * Calculates the beat period of two line gratings.
     *
     * The beat period is the reciprocal of the magnitude of the beat wave vector.
     * If the magnitude is very small (less than 1e-12), returns Double.POSITIVE_INFINITY.
     *
     * @param p1 the pitch of grating 1
     * @param p2 the pitch of grating 2
     * @param theta the rotation angle of grating 2 in radians
     * @return the beat period, or Double.POSITIVE_INFINITY if no fringe pattern exists
     */
    fun beatPeriod(p1: Double, p2: Double, theta: Double): Double {
        val kb = beatVector(p1, p2, theta)
        val kbx = kb[0]
        val kby = kb[1]
        val magnitude = sqrt(kbx * kbx + kby * kby)
        
        if (magnitude < 1e-12) {
            return Double.POSITIVE_INFINITY
        }
        
        return 1.0 / magnitude
    }

    /**
     * Calculates the angle of the normal to the fringe lines.
     *
     * This is the angle of the beat wave vector in radians.
     * If the magnitude of the beat vector is very small, returns 0.0.
     *
     * @param p1 the pitch of grating 1
     * @param p2 the pitch of grating 2
     * @param theta the rotation angle of grating 2 in radians
     * @return the angle of the normal to the fringe lines in radians
     */
    fun fringeNormalAngle(p1: Double, p2: Double, theta: Double): Double {
        val kb = beatVector(p1, p2, theta)
        val kbx = kb[0]
        val kby = kb[1]
        val magnitude = sqrt(kbx * kbx + kby * kby)
        
        if (magnitude < 1e-12) {
            return 0.0
        }
        
        return atan2(kby, kbx)
    }

    /**
     * Calculates the angle of the fringe lines themselves.
     *
     * This is the normal angle plus π/2, wrapped to the range (-π/2, π/2].
     *
     * @param p1 the pitch of grating 1
     * @param p2 the pitch of grating 2
     * @param theta the rotation angle of grating 2 in radians
     * @return the angle of the fringe lines in radians
     */
    fun fringeLineAngle(p1: Double, p2: Double, theta: Double): Double {
        val normalAngle = fringeNormalAngle(p1, p2, theta)
        var lineAngle = normalAngle + PI / 2
        
        // Wrap to (-PI/2, PI/2]
        while (lineAngle <= -PI / 2) {
            lineAngle += PI
        }
        while (lineAngle > PI / 2) {
            lineAngle -= PI
        }
        
        return lineAngle
    }
}
